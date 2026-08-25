<#
.SYNOPSIS
    The pre-PR sweep — every class of error/warning OpenLoop knows how to detect, run to ZERO.

.DESCRIPTION
    Runs, in order, and reports every gate (it does not stop at the first red):
      1. Gradle: clean assembleDebug + assembleRelease — BUILD SUCCESSFUL, exit 0, zero `e:` AND zero
         source-attributed `w: file:…` (Kotlin warnings are already fatal via allWarningsAsErrors; build-script
         deprecations are caught here; KGP's environmental daemon notices are not findings).
      2. 16 KB alignment: zipalign -c -P 16 on the release APK — every .so "(OK)", none "(OK - compressed)".
      3. Android Lint (Engine 1): 0 Error/Fatal AND 0 Warning in lint-results-debug.xml. The version-freshness
         checks (GradleDependency, NewerVersionAvailable, AndroidGradlePluginVersion) are reported but never fail
         the gate — they flip whenever upstream publishes and a gate that goes red on somebody else's schedule
         is a flaky gate (docs/STATIC_ANALYSIS.md).
      4. JVM unit tests: 0 failures, 0 errors, tests > 0 (counted from the XML, never from BUILD SUCCESSFUL).
      5. Instrumented tests (unless -SkipConnected): same, from the connected XML.
      6. Markdown: markdownlint-cli2, table alignment (scripts/md-table-align.py), markdown-link-check — all zero.
      7. Spelling: cspell over every tracked text file (Markdown, Kotlin, XML, scripts, configs) — zero unknown words.
         Legit terms go into cspell.json `words` (never disable the check).
      8. JSON validity of every tracked *.json; the IDE dictionary is in sync with cspell.json.
      9. Inspect Code (Engine 2): parses the Android Studio HTML export with scripts/inspect-report.py — zero hard
         findings in tracked files. Run Code → Inspect Code with the "OpenLoop Tracked" scope and export HTML
         to build/inspect-export/. Pass -SkipInspectCode ONLY where Studio is unavailable; the receipt then says
         so and the PR description must say so too.

    On an all-green run it writes build/sweep-receipt.json {sha, treeClean, gates, inspectCode}. The Claude Code
    PreToolUse hook (scripts/hooks/require-sweep.mjs) refuses `gh pr create` / GitHub create_pull_request unless a
    receipt exists for the CURRENT HEAD on a clean tree — so the sweep is definitionally the last thing that runs
    after the final commit. Design + rationale: docs/DEFINITION_OF_DONE.md, docs/STATIC_ANALYSIS.md.

.PARAMETER InspectExport   Path to the Inspect Code HTML export. Default: build/inspect-export/index.html
.PARAMETER SkipInspectCode Record Engine 2 as NOT RUN instead of failing when the export is missing.
.PARAMETER SkipConnected   Skip connectedDebugAndroidTest (no emulator/device attached).
.PARAMETER DocsOnly        Text gates only (6-9). For docs-only branches — the receipt records it.

.EXAMPLE
    .\scripts\pre-pr-sweep.ps1
    .\scripts\pre-pr-sweep.ps1 -SkipConnected -SkipInspectCode   # agent session without Studio or an emulator
#>
[CmdletBinding()]
param(
    [string]$InspectExport = "build/inspect-export/index.html",
    [switch]$SkipInspectCode,
    [switch]$SkipConnected,
    [switch]$DocsOnly
)

$ErrorActionPreference = "Continue"
$root = (git rev-parse --show-toplevel 2>$null)
if (-not $root) { Write-Error "Not inside a git checkout."; exit 2 }
Set-Location $root
New-Item -ItemType Directory -Force build | Out-Null
$log = Join-Path $root "build/sweep.log"
"pre-pr-sweep $(Get-Date -Format o)" | Set-Content $log

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }

$results = [ordered]@{}
function Gate([string]$name, [scriptblock]$body) {
    Write-Host ""
    Write-Host "== $name" -ForegroundColor Cyan
    "== $name" | Add-Content $log
    try {
        $verdict = & $body
        if ($verdict -isnot [string]) { $verdict = "PASS" }
    } catch {
        $verdict = "FAIL: $($_.Exception.Message)"
    }
    $ok = $verdict -like "PASS*" -or $verdict -like "SKIPPED*"
    $color = if ($verdict -like "PASS*") { "Green" } elseif ($verdict -like "SKIPPED*") { "Yellow" } else { "Red" }
    Write-Host "   -> $verdict" -ForegroundColor $color
    "   -> $verdict" | Add-Content $log
    $script:results[$name] = $verdict
}

function Run-Gradle([string[]]$tasks) {
    $out = & .\gradlew.bat @tasks --console=plain 2>&1
    $code = $LASTEXITCODE
    $out | Add-Content $log
    return @{ Code = $code; Lines = @($out | ForEach-Object { "$_" }) }
}

function Tracked([string[]]$globs) {
    return @(git ls-files -- $globs | Where-Object { Test-Path $_ })
}

function Sum-JUnit([string]$dir) {
    $t = 0; $f = 0; $e = 0
    Get-ChildItem $dir -Recurse -Filter *.xml -ErrorAction SilentlyContinue | ForEach-Object {
        # Attribute order differs between the JVM and the connected reports — read each one on its own.
        $head = Get-Content $_.FullName -Raw
        if ($head -match '<testsuite\b') {
            if ($head -match '\btests="(\d+)"') { $t += [int]$Matches[1] }
            if ($head -match '\bfailures="(\d+)"') { $f += [int]$Matches[1] }
            if ($head -match '\berrors="(\d+)"') { $e += [int]$Matches[1] }
        }
    }
    return @{ Tests = $t; Failures = $f; Errors = $e }
}

# ---------------------------------------------------------------------------- build gates
if (-not $DocsOnly) {
    Gate "1. clean assembleDebug assembleRelease (0 e:, 0 w:)" {
        $r = Run-Gradle @("clean", "assembleDebug", "assembleRelease")
        $errs = @($r.Lines | Where-Object { $_ -match '^e: ' })
        # Source-attributed warnings only (`w: file:///…:line:col`): Kotlin compiler + build-script
        # deprecations. KGP also prints environmental `w:` notices ("Detected multiple Kotlin daemon
        # sessions") that no code change can clear — those are not findings.
        $warns = @($r.Lines | Where-Object { $_ -match '^w: file:' })
        $ok = ($r.Code -eq 0) -and ($r.Lines -match 'BUILD SUCCESSFUL') -and $errs.Count -eq 0 -and $warns.Count -eq 0
        if ($ok) { return "PASS (exit 0, BUILD SUCCESSFUL, 0 e:, 0 w:)" }
        return "FAIL: exit=$($r.Code) e:=$($errs.Count) w:=$($warns.Count) — first: $(($errs + $warns | Select-Object -First 1))"
    }

    Gate "2. zipalign -c -P 16 on the release APK" {
        $apk = @("app/build/outputs/apk/release/app-release-unsigned.apk", "app/build/outputs/apk/release/app-release.apk") | Where-Object { Test-Path $_ } | Select-Object -First 1
        if (-not $apk) { return "FAIL: no release APK found" }
        $za = Get-ChildItem "$sdk/build-tools" -Directory | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName "zipalign.exe" } | Where-Object { Test-Path $_ } | Select-Object -First 1
        if (-not $za) { return "FAIL: zipalign.exe not found under $sdk/build-tools" }
        $out = & $za -c -P 16 -v 4 $apk 2>&1
        $out | Add-Content $log
        $so = @($out | Where-Object { $_ -match '\.so ' })
        $bad = @($so | Where-Object { $_ -notmatch '\(OK\)\s*$' })
        if ($LASTEXITCODE -eq 0 -and $so.Count -gt 0 -and $bad.Count -eq 0) { return "PASS ($($so.Count) .so entries all (OK), uncompressed)" }
        return "FAIL: exit=$LASTEXITCODE so=$($so.Count) not-plain-OK=$($bad.Count) — $($bad | Select-Object -First 1)"
    }

    Gate "3. Android Lint — 0 errors, 0 warnings (freshness checks advisory)" {
        $r = Run-Gradle @(":app:lintDebug")
        $xml = "app/build/reports/lint-results-debug.xml"
        if ($r.Code -ne 0 -or -not (Test-Path $xml)) { return "FAIL: lint exit=$($r.Code), report=$(Test-Path $xml)" }
        [xml]$doc = Get-Content $xml -Raw
        $advisory = @("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        $issues = @($doc.issues.issue)
        $hard = @($issues | Where-Object { $_.severity -in @("Error", "Fatal", "Warning") -and $_.id -notin $advisory })
        $adv = @($issues | Where-Object { $_.id -in $advisory })
        if ($hard.Count -eq 0) { return "PASS (0 hard findings; $($adv.Count) advisory version-freshness)" }
        return "FAIL: $($hard.Count) lint finding(s) — $(($hard | Select-Object -First 3 | ForEach-Object { "$($_.id)@$($_.location.file):$($_.location.line)" }) -join ', ')"
    }

    Gate "4. JVM unit tests — 0 failures" {
        $r = Run-Gradle @(":app:testDebugUnitTest")
        $s = Sum-JUnit "app/build/test-results/testDebugUnitTest"
        if ($r.Code -eq 0 -and $s.Tests -gt 0 -and $s.Failures -eq 0 -and $s.Errors -eq 0) { return "PASS ($($s.Tests) tests, 0 failures, 0 errors)" }
        return "FAIL: exit=$($r.Code) tests=$($s.Tests) failures=$($s.Failures) errors=$($s.Errors)"
    }

    Gate "5. Instrumented tests — 0 failures" {
        if ($SkipConnected) { return "SKIPPED (-SkipConnected; run connectedDebugAndroidTest before the PR)" }
        $devices = @(& "$sdk/platform-tools/adb.exe" devices 2>$null | Select-String -Pattern "^\S+\s+(device|offline)$")
        if ($devices.Count -gt 1 -and -not $env:ANDROID_SERIAL) {
            return "FAIL: $($devices.Count) devices attached and ANDROID_SERIAL is unset — pin one (a Studio-managed emulator that is `offline` will otherwise be picked)"
        }
        $r = Run-Gradle @(":app:connectedDebugAndroidTest")
        $s = Sum-JUnit "app/build/outputs/androidTest-results/connected"
        if ($r.Code -eq 0 -and $s.Tests -gt 0 -and $s.Failures -eq 0 -and $s.Errors -eq 0) { return "PASS ($($s.Tests) tests, 0 failures, 0 errors)" }
        return "FAIL: exit=$($r.Code) tests=$($s.Tests) failures=$($s.Failures) errors=$($s.Errors)"
    }
}

# ---------------------------------------------------------------------------- text gates
$md = Tracked @("*.md")
$textGlobs = @("*.md", "*.kt", "*.kts", "*.xml", "*.yml", "*.yaml", "*.ps1", "*.py", "*.mjs", "*.js", "*.json", "*.jsonc",
               "*.properties", "*.html", "*.toml", "*.pro", "*.template", "*.README", "*.txt", "*.dic")
$text = Tracked $textGlobs
$listFile = Join-Path $root "build/sweep-files.txt"
$text | Set-Content $listFile -Encoding utf8

Gate "6a. markdownlint-cli2 — 0 findings" {
    $out = & npx --yes markdownlint-cli2 @md 2>&1
    $out | Add-Content $log
    if ($LASTEXITCODE -eq 0) { return "PASS ($($md.Count) files)" }
    return "FAIL: $(($out | Where-Object { $_ -match 'Summary:' }) -join ' ')"
}

Gate "6b. Markdown table alignment (IDE-faithful) — 0 misaligned" {
    $out = & python scripts/md-table-align.py 2>&1
    $out | Add-Content $log
    if ($LASTEXITCODE -eq 0) { return "PASS" }
    return "FAIL: $($out | Select-Object -Last 1) (fix: python scripts/md-table-align.py --fix)"
}

Gate "6c. markdown-link-check — 0 dead relative links" {
    $dead = 0
    foreach ($f in $md) {
        $out = & npx --yes markdown-link-check --config .markdown-link-check.json -q $f 2>&1
        $out | Add-Content $log
        $dead += @($out | Where-Object { $_ -match '\[✖\]|\[x\]|ERROR:' }).Count
    }
    if ($dead -eq 0) { return "PASS ($($md.Count) files)" }
    return "FAIL: $dead dead link(s) — see build/sweep.log"
}

Gate "7. cspell over every tracked text file — 0 unknown words" {
    # `--file-list <path>` exits 1 silently on Windows; feeding the list on stdin works everywhere.
    $out = Get-Content $listFile | npx --yes cspell --no-progress --file-list stdin 2>&1
    $out | Add-Content $log
    if ($LASTEXITCODE -eq 0) { return "PASS ($($text.Count) files)" }
    return "FAIL: $(($out | Where-Object { $_ -match 'Issues found' }) -join ' ') — add legit terms to cspell.json words"
}

Gate "8a. JSON validity" {
    $bad = @()
    foreach ($f in (Tracked @("*.json"))) {
        $raw = Get-Content $f -Raw
        if ($f -eq "cspell.json") { $raw = [regex]::Replace($raw, '(?m)^\s*//.*$', '') }
        try { $null = $raw | ConvertFrom-Json } catch { $bad += $f }
    }
    if ($bad.Count -eq 0) { return "PASS" }
    return "FAIL: $($bad -join ', ')"
}

Gate "8b. IDE spelling dictionary in sync with cspell.json" {
    $out = & python scripts/sync-ide-dictionary.py --check 2>&1
    $out | Add-Content $log
    if ($LASTEXITCODE -eq 0) { return "PASS" }
    return "FAIL: $out"
}

$inspect = "skipped"
Gate "9. Inspect Code export (Engine 2) — 0 hard findings in tracked files" {
    if (Test-Path $InspectExport) {
        $out = & python scripts/inspect-report.py $InspectExport --tsv build/inspect-problems.tsv 2>&1
        $out | Add-Content $log
        if ($LASTEXITCODE -eq 0) { $script:inspect = "passed"; return "PASS ($($out | Select-Object -Last 1))" }
        return "FAIL: $($out | Select-Object -Last 1) — build/inspect-problems.tsv"
    }
    if ($SkipInspectCode) { return "SKIPPED (-SkipInspectCode: say so in the PR; the owner runs Inspect Code before merge)" }
    return "FAIL: no export at $InspectExport. Android Studio → Code → Inspect Code → scope 'OpenLoop Tracked' → Export → HTML → build/inspect-export/"
}

# ---------------------------------------------------------------------------- verdict + receipt
Write-Host ""
Write-Host "== Sweep summary" -ForegroundColor Cyan
$failed = @()
foreach ($k in $results.Keys) {
    $v = $results[$k]
    $color = if ($v -like "PASS*") { "Green" } elseif ($v -like "SKIPPED*") { "Yellow" } else { "Red"; }
    if ($v -notlike "PASS*" -and $v -notlike "SKIPPED*") { $failed += $k }
    Write-Host ("  {0,-70} {1}" -f $k, $v) -ForegroundColor $color
}

$sha = (git rev-parse HEAD).Trim()
$dirty = @(git status --porcelain --untracked-files=no)
$receipt = [ordered]@{
    sha         = $sha
    branch      = (git branch --show-current).Trim()
    at          = (Get-Date -Format o)
    treeClean   = ($dirty.Count -eq 0)
    docsOnly    = [bool]$DocsOnly
    inspectCode = $inspect
    connected   = (-not $SkipConnected) -and (-not $DocsOnly)
    gates       = $results
}
$receiptPath = Join-Path $root "build/sweep-receipt.json"
if ($failed.Count -gt 0) {
    if (Test-Path $receiptPath) { Remove-Item $receiptPath }
    Write-Host ""
    Write-Host "SWEEP FAILED — $($failed.Count) gate(s) red. No receipt written. Log: build/sweep.log" -ForegroundColor Red
    exit 1
}
$receipt | ConvertTo-Json -Depth 4 | Set-Content $receiptPath -Encoding utf8
Write-Host ""
if (-not $receipt.treeClean) {
    Write-Host "SWEEP GREEN for $sha — but the tree has uncommitted tracked changes; commit, then re-run so the receipt matches HEAD." -ForegroundColor Yellow
} else {
    Write-Host "SWEEP GREEN for $sha — receipt: build/sweep-receipt.json (inspectCode=$inspect)" -ForegroundColor Green
}
exit 0
