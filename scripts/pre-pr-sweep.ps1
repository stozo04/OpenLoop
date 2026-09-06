<#
.SYNOPSIS
    The pre-PR sweep — every class of error/warning OpenLoop knows how to detect, run to ZERO.

.DESCRIPTION
    Runs the fast text gates first, then every build/device gate, and reports every result (it does not
    stop at the first red):
      1. Gradle: assembleDebug + assembleRelease + Lint + JVM tests in one invocation — BUILD SUCCESSFUL,
         exit 0, zero `e:` AND zero
         source-attributed `w: file:…` (Kotlin warnings are already fatal via allWarningsAsErrors; build-script
         deprecations are caught here; KGP's environmental daemon notices are not findings).
      2. 16 KB alignment: zipalign -c -P 16 on the release APK — every .so "(OK)", none "(OK - compressed)".
      3. Android Lint (Engine 1): parse the merged run's report; 0 Error/Fatal AND 0 Warning in
         lint-results-debug.xml. The version-freshness
         checks (GradleDependency, NewerVersionAvailable, AndroidGradlePluginVersion) are reported but never fail
         the gate — they flip whenever upstream publishes and a gate that goes red on somebody else's schedule
         is a flaky gate (docs/STATIC_ANALYSIS.md).
      4. JVM unit tests: parse the merged run's report; 0 failures, 0 errors, tests > 0 (counted from the
         XML, never from BUILD SUCCESSFUL).
      5. Instrumented tests (unless -SkipConnected): same, from the connected XML.
      5b. Installed-app loops via scripts/run-verification-loops.py --all.
         Starts after a green merged Gradle run. One emulator — does not overlap
         connectedDebugAndroidTest. Skip with -SkipConnected.
      6. Markdown: markdownlint-cli2, table alignment (scripts/md-table-align.py), and relative-link checks on
         changed Markdown (all Markdown after any delete/rename) — all zero.
      7. Spelling: cspell over every tracked text file (Markdown, Kotlin, XML, scripts, configs) — zero unknown words.
         Legit terms go into cspell.json `words` (never disable the check).
      8. JSON validity, IDE dictionary sync, tracked-file hygiene, and a redacted Gitleaks scan of tracked HEAD.
      9. Inspect Code (Engine 2): parses the Android Studio HTML export with scripts/inspect-report.py — zero hard
         findings in tracked files. Run Code → Inspect Code with the "OpenLoop Tracked" scope and export HTML
         to build/inspect-export/. Pass -SkipInspectCode ONLY where Studio is unavailable; the receipt then says
         so and the PR description must say so too.

    On an all-green run it writes build/sweep-receipt.json {sha, treeClean, cleanBuild, gates,
    durationSec, inspectCode}. The Claude Code
    PreToolUse hook (scripts/hooks/require-sweep.mjs) refuses `gh pr create` / GitHub create_pull_request unless a
    receipt exists for the CURRENT HEAD on a clean tree — so the sweep is definitionally the last thing that runs
    after the final commit. Design + rationale: docs/DEFINITION_OF_DONE.md, docs/STATIC_ANALYSIS.md.

.PARAMETER InspectExport   Path to the Inspect Code HTML export. Default: build/inspect-export/index.html
.PARAMETER SkipInspectCode Record Engine 2 as NOT RUN instead of failing when the export is missing.
.PARAMETER SkipConnected   Skip connectedDebugAndroidTest and the onboarding loop (no emulator/device attached).
                           A skip means onboarding is not verified.
.PARAMETER DocsOnly        Text gates only (6-9). For docs-only branches — the receipt records it.
.PARAMETER Clean           Prepend Gradle clean. Use after build-tool/dependency changes or suspected stale outputs.
.PARAMETER RerunTests      Execute JVM tests even when Gradle could reuse their results. Required for releases.

.EXAMPLE
    .\scripts\pre-pr-sweep.ps1
    .\scripts\pre-pr-sweep.ps1 -Clean                    # cold build/tooling verification
    .\scripts\pre-pr-sweep.ps1 -SkipConnected -SkipInspectCode   # agent session without Studio or an emulator
#>
[CmdletBinding()]
param(
    [string]$InspectExport = "build/inspect-export/index.html",
    [switch]$SkipInspectCode,
    [switch]$SkipConnected,
    [switch]$DocsOnly,
    [switch]$Clean,
    [switch]$RerunTests
)

$ErrorActionPreference = "Continue"
$root = (git rev-parse --show-toplevel 2>$null)
if (-not $root) { Write-Error "Not inside a git checkout."; exit 2 }
Set-Location $root
$headAtStart = (git rev-parse HEAD).Trim()
$dirtyAtStart = @(git status --porcelain)
New-Item -ItemType Directory -Force build | Out-Null
$history = Join-Path $root "build/sweep-history/$(Get-Date -Format yyyyMMdd-HHmmss-fffffff)"
$previous = @("sweep.log", "sweep-receipt.json", "verification-loops.log", "verification-loops.err", "verification-loops-receipt.json")
foreach ($name in $previous) {
    $path = Join-Path $root "build/$name"
    if (Test-Path -LiteralPath $path) {
        New-Item -ItemType Directory -Force -Path $history -ErrorAction Stop | Out-Null
        Move-Item -LiteralPath $path -Destination (Join-Path $history $name) -ErrorAction Stop
    }
}
$log = Join-Path $root "build/sweep.log"
"pre-pr-sweep $(Get-Date -Format o)" | Set-Content $log

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$markdownLint = Join-Path $root "node_modules/markdownlint-cli2/markdownlint-cli2-bin.mjs"
$markdownLinkCheck = Join-Path $root "node_modules/markdown-link-check/markdown-link-check"
$cspell = Join-Path $root "node_modules/cspell/bin.mjs"

$results = [ordered]@{}
$timings = [ordered]@{}
$gradleTasks = [ordered]@{}
$testResults = [ordered]@{}
$artifacts = [ordered]@{}
$loopReceipt = $null
$loopReceiptPath = Join-Path $root "build/verification-loops-receipt.json"
$loopProc = $null
$loopStartError = $null
$loopLog = Join-Path $root "build/verification-loops.log"
$loopErr = Join-Path $root "build/verification-loops.err"
function Gate([string]$name, [scriptblock]$body) {
    Write-Host ""
    Write-Host "== $name" -ForegroundColor Cyan
    "== $name" | Add-Content $log
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $verdict = & $body
        if ($verdict -isnot [string]) { $verdict = "PASS" }
    } catch {
        $verdict = "FAIL: $($_.Exception.Message)"
    }
    $sw.Stop()
    $color = if ($verdict -like "PASS*") { "Green" } elseif ($verdict -like "SKIPPED*") { "Yellow" } else { "Red" }
    Write-Host "   -> $verdict" -ForegroundColor $color
    "   -> $verdict" | Add-Content $log
    Write-Host ("   [{0:N1}s]" -f $sw.Elapsed.TotalSeconds) -ForegroundColor DarkGray
    ("   [{0:N1}s]" -f $sw.Elapsed.TotalSeconds) | Add-Content $log
    $script:timings[$name] = [math]::Round($sw.Elapsed.TotalSeconds, 1)
    $script:results[$name] = $verdict
}

function Run-Gradle([string[]]$tasks) {
    $out = & .\gradlew.bat @tasks --profile --console=plain 2>&1
    $code = $LASTEXITCODE
    $out | Add-Content $log
    foreach ($line in $out) {
        if ("$line" -match '^> Task (\S+)(?: (.+))?$') {
            $script:gradleTasks[$Matches[1]] = if ($Matches[2]) { $Matches[2] } else { "EXECUTED" }
        }
    }
    return @{ Code = $code; Lines = @($out | ForEach-Object { "$_" }) }
}

function Tracked([string[]]$globs) {
    return @(git ls-files -- $globs | Where-Object { Test-Path $_ })
}

function Sum-JUnit([string]$dir) {
    $ErrorActionPreference = "Stop"
    $t = 0; $f = 0; $e = 0; $skipped = 0; $seconds = 0.0
    foreach ($file in (Get-ChildItem $dir -Recurse -Filter *.xml)) {
        [xml]$report = Get-Content -LiteralPath $file.FullName -Raw
        $suites = $report.SelectNodes('/testsuite | /testsuites/testsuite')
        if ($suites.Count -eq 0) { throw "No JUnit suite in $($file.FullName)" }
        foreach ($suite in $suites) {
            foreach ($attribute in @('tests', 'failures', 'errors')) {
                if ($suite.GetAttribute($attribute) -notmatch '^\d+$') { throw "Invalid JUnit $attribute in $($file.FullName)" }
            }
            if ($suite.SelectNodes('testcase').Count -ne [int]$suite.tests) { throw "JUnit testcase count disagrees with tests in $($file.FullName)" }
            $t += [int]$suite.tests
            $f += [int]$suite.failures
            $e += [int]$suite.errors
            $skipped += $suite.SelectNodes('testcase/skipped').Count
            $seconds += [double]$suite.time
        }
    }
    return @{ Tests = $t; Failures = $f; Errors = $e; Skipped = $skipped; TestTimeSec = [math]::Round($seconds, 3) }
}

function Remove-ReportDirectory([string]$path) {
    $workspace = [IO.Path]::GetFullPath($root).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $target = [IO.Path]::GetFullPath($path)
    if (-not $target.StartsWith($workspace, [StringComparison]::OrdinalIgnoreCase)) { throw "Report directory outside checkout: $target" }
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction Stop }
}

function Test-FullLoopReceipt($receipt, [string[]]$expected, [string]$apkSha) {
    $selected = @($receipt.selected | Sort-Object)
    $executed = @($receipt.executed | Where-Object { $_.exitCode -eq 0 } | ForEach-Object { $_.name } | Sort-Object)
    return $expected.Count -gt 0 -and $apkSha -and $receipt.status -eq "passed" -and
        $receipt.apkSha256 -eq $apkSha -and @($receipt.skipped).Count -eq 0 -and
        ($expected -join ',') -eq ($selected -join ',') -and
        ($expected -join ',') -eq ($executed -join ',')
}

# ---------------------------------------------------------------------------- build gates (invoked after text gates)
$buildGates = {
if (-not $DocsOnly) {
    $buildGateName = "1. assembleDebug assembleRelease + lint + JVM tests (0 e:, 0 w:)"
    $unitResultsDir = Join-Path $root "app/build/test-results/testDebugUnitTest"
    $lintResults = Join-Path $root "app/build/reports/lint-results-debug.xml"
    Gate $buildGateName {
        # Dropping `clean` preserves Gradle's cache, but stale reports must never count as this run.
        Remove-ReportDirectory $unitResultsDir
        if (Test-Path -LiteralPath $lintResults) { Remove-Item -LiteralPath $lintResults -Force -ErrorAction Stop }
        $tasks = @(
            "assembleDebug",
            "assembleRelease",
            ":app:lintDebug",
            ":app:testDebugUnitTest"
        )
        if ($RerunTests) { $tasks += "--rerun" }
        $tasks += @(
            "--parallel",
            "--build-cache",
            "--continue",
            "-PopenloopVerification=true"
        )
        if (-not $SkipConnected) { $tasks += ":app:assembleDebugAndroidTest" }
        if ($Clean) { $tasks = [string[]]"clean" + $tasks }
        $r = Run-Gradle $tasks
        $errs = @($r.Lines | Where-Object { $_ -match '^e: ' })
        # Source-attributed warnings only (`w: file:///…:line:col`): Kotlin compiler + build-script
        # deprecations. KGP also prints environmental `w:` notices ("Detected multiple Kotlin daemon
        # sessions") that no code change can clear — those are not findings.
        $warns = @($r.Lines | Where-Object { $_ -match '^w: file:' })
        $ok = ($r.Code -eq 0) -and ($r.Lines -match 'BUILD SUCCESSFUL') -and $errs.Count -eq 0 -and $warns.Count -eq 0
        if ($ok) {
            $apkPaths = @("app/build/outputs/apk/debug/app-debug.apk")
            if (-not $SkipConnected) { $apkPaths += "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" }
            foreach ($apk in $apkPaths) {
                if (-not (Test-Path -LiteralPath $apk)) { return "FAIL: build reported success but APK is missing: $apk" }
                $script:artifacts[$apk] = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
            }
            return "PASS (exit 0, BUILD SUCCESSFUL, 0 e:, 0 w:; clean=$([bool]$Clean))"
        }
        return "FAIL: exit=$($r.Code) e:=$($errs.Count) w:=$($warns.Count) — first: $(($errs + $warns | Select-Object -First 1))"
    }

    # Connected tests wait until the onboarding proof finishes because both drive the same emulator.
    if (-not $SkipConnected -and ($results[$buildGateName] -like "PASS*")) {
        try {
            if (Test-Path $loopLog) { Remove-Item $loopLog -Force }
            if (Test-Path $loopErr) { Remove-Item $loopErr -Force }
            $script:loopProc = Start-Process -FilePath "python" -ArgumentList @(
                (Join-Path $root "scripts/run-verification-loops.py"),
                "--all", "--report", $loopReceiptPath
            ) -WorkingDirectory $root -PassThru -WindowStyle Hidden `
                -RedirectStandardOutput $loopLog -RedirectStandardError $loopErr -ErrorAction Stop
            if ($null -eq $script:loopProc) { $script:loopStartError = "Start-Process returned null" }
            Write-Host "== 5b. Installed-app loops — started in background" -ForegroundColor Cyan
        } catch {
            $script:loopStartError = $_.Exception.Message
        }
    }

    Gate "2. zipalign -c -P 16 on the release APK" {
        $apk = @("app/build/outputs/apk/release/app-release-unsigned.apk", "app/build/outputs/apk/release/app-release.apk") | Where-Object { Test-Path $_ } | Select-Object -First 1
        if (-not $apk) { return "FAIL: no release APK found" }
        $script:artifacts[$apk] = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
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
        $xml = "app/build/reports/lint-results-debug.xml"
        if (-not (Test-Path $xml)) { return "FAIL: no lint report (merged Gradle run did not reach lintDebug)" }
        [xml]$doc = Get-Content $xml -Raw
        $advisory = @("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        $issues = @($doc.issues.issue)
        $hard = @($issues | Where-Object { $_.severity -in @("Error", "Fatal", "Warning") -and $_.id -notin $advisory })
        $adv = @($issues | Where-Object { $_.id -in $advisory })
        if ($hard.Count -eq 0) { return "PASS (0 hard findings; $($adv.Count) advisory version-freshness)" }
        return "FAIL: $($hard.Count) lint finding(s) — $(($hard | Select-Object -First 3 | ForEach-Object { "$($_.id)@$($_.location.file):$($_.location.line)" }) -join ', ')"
    }

    Gate "4. JVM unit tests — 0 failures" {
        $s = Sum-JUnit "app/build/test-results/testDebugUnitTest"
        $s.Execution = $gradleTasks[":app:testDebugUnitTest"]
        $script:testResults["jvm"] = $s
        if ($RerunTests -and $s.Execution -ne "EXECUTED") { return "FAIL: -RerunTests requested execution, got $($s.Execution)" }
        if ($s.Tests -gt 0 -and $s.Failures -eq 0 -and $s.Errors -eq 0 -and $s.Execution -in @("EXECUTED", "FROM-CACHE", "UP-TO-DATE")) {
            return "PASS ($($s.Tests) tests, $($s.Skipped) skipped, 0 failures, 0 errors; $($s.Execution))"
        }
        return "FAIL: tests=$($s.Tests) failures=$($s.Failures) errors=$($s.Errors)"
    }
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
    if (-not (Test-Path -LiteralPath $markdownLint)) { return "FAIL: pinned npm tools missing (run npm ci --ignore-scripts)" }
    # Invoke Node directly: one tracked-file-scoped process without cmd.exe's 8,191-character limit.
    $out = & node $markdownLint @($md | ForEach-Object { ":$_" }) 2>&1
    $code = $LASTEXITCODE
    $out | Add-Content $log
    $linted = @($out | ForEach-Object { if ($_ -match '^Linting: (\d+) files?$') { [int]$Matches[1] } } | Measure-Object -Sum).Sum
    if ($code -eq 0 -and $linted -eq $md.Count) { return "PASS ($linted files)" }
    return "FAIL: exit=$code linted=$linted expected=$($md.Count) $(($out | Where-Object { $_ -match 'Summary:' }) -join ' ')"
}

Gate "6b. Markdown table alignment (IDE-faithful) — 0 misaligned" {
    $out = & python scripts/md-table-align.py 2>&1
    $out | Add-Content $log
    if ($LASTEXITCODE -eq 0) { return "PASS" }
    return "FAIL: $($out | Select-Object -Last 1) (fix: python scripts/md-table-align.py --fix)"
}

$linkBase = ("$(& git merge-base HEAD origin/main 2>$null)").Trim()
$removedOrRenamed = if ($linkBase) { @(& git diff --name-only --diff-filter=DR --find-renames $linkBase) } else { @() }
$linkMd = if ($removedOrRenamed.Count -gt 0) {
    $md
} elseif ($linkBase) {
    @(& git diff --name-only --diff-filter=ACMRT $linkBase -- "*.md" | Where-Object { Test-Path $_ })
} else {
    @()
}

Gate "6c. markdown-link-check — changed docs (all after delete/rename)" {
    if (-not $linkBase) { return "FAIL: cannot find merge-base with origin/main" }
    if ($linkMd.Count -gt 0 -and -not (Test-Path -LiteralPath $markdownLinkCheck)) { return "FAIL: pinned npm tools missing (run npm ci --ignore-scripts)" }
    $dead = 0
    $toolFailed = $false
    foreach ($f in $linkMd) {
        $out = & node $markdownLinkCheck --config .markdown-link-check.json -q $f 2>&1
        if ($LASTEXITCODE -ne 0) { $toolFailed = $true }
        $out | Add-Content $log
        $dead += @($out | Where-Object { $_ -match '\[✖\]|\[x\]|ERROR:' }).Count
    }
    $scope = if ($removedOrRenamed.Count -gt 0) { "all; delete/rename detected" } else { "changed" }
    if (-not $toolFailed -and $dead -eq 0) { return "PASS ($($linkMd.Count) files; $scope)" }
    return "FAIL: tool-failed=$toolFailed dead-links=$dead — see build/sweep.log"
}

Gate "6d. Harness skill trees byte-identical (.claude/.cursor/.codex)" {
    $out = & python scripts/sync-harness-skills.py --check 2>&1
    $out | Add-Content $log
    if ($LASTEXITCODE -eq 0) { return "PASS" }
    return "FAIL: skill trees have drifted — see build/sweep.log for the paths"
}

Gate "6e. Script self-checks (scripts/test-*.py) — all green" {
    # Free and offline. The other gates prove the TEXT is well-formed; this is the only one that
    # fails when the logic of a script those gates depend on breaks.
    $checks = @(Get-ChildItem scripts/test-*.py)
    $completed = @($checks | ForEach-Object -Parallel {
        Set-Location $using:root
        $out = & python $_.FullName 2>&1
        $code = $LASTEXITCODE
        [pscustomobject]@{ Name = $_.Name; Code = $code; Lines = $out }
    } -ThrottleLimit 3)
    foreach ($check in ($completed | Sort-Object Name)) {
        $check.Lines | Add-Content $log
    }
    $bad = @($completed | Where-Object { $_.Code -ne 0 } | ForEach-Object { $_.Name })
    if ($completed.Count -eq $checks.Count -and $checks.Count -gt 0 -and $bad.Count -eq 0) { return "PASS ($($completed.Count) scripts executed)" }
    return "FAIL: completed=$($completed.Count)/$($checks.Count) failed=$($bad -join ', ') — see build/sweep.log"
}

Gate "7. cspell over every tracked text file — 0 unknown words" {
    # `--file-list <path>` exits 1 silently on Windows; feeding the list on stdin works everywhere.
    if (-not (Test-Path -LiteralPath $cspell)) { return "FAIL: pinned npm tools missing (run npm ci --ignore-scripts)" }
    $out = Get-Content $listFile | node $cspell --no-progress --file-list stdin 2>&1
    $out | Add-Content $log
    if ($LASTEXITCODE -eq 0) { return "PASS ($($text.Count) files)" }
    return "FAIL: $(($out | Where-Object { $_ -match 'Issues found' }) -join ' ') — add legit terms to cspell.json words"
}

Gate "8a. JSON validity" {
    $bad = @()
    foreach ($f in (Tracked @("*.json"))) {
        $raw = Get-Content $f -Raw
        if ($f -eq "cspell.json") { $raw = [regex]::Replace($raw, '(?m)^\s*//.*$', '') }
        try { [System.Text.Json.JsonDocument]::Parse($raw).Dispose() } catch { $bad += $f }
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

Gate "8c. Tracked-file hygiene + Gitleaks — no generated files or secrets" {
    $ignoredTracked = @(& git ls-files -ci --exclude-standard)
    $ignoredTracked | Add-Content $log
    # An XML file outside res/ that declares the Android namespace cannot resolve it in ANY IDE —
    # Inspect Code reports "URI is not registered" on every open. Generator input (swarm/art) carries
    # plain attribute names instead; the manifest is the one legitimate exception. (1.0.50, #169)
    $strayNs = @(& git ls-files "*.xml" | Where-Object { $_ -notmatch "/res/" -and $_ -notmatch "AndroidManifest\.xml$" } |
        Where-Object { Select-String -Path $_ -Pattern "schemas\.android\.com" -Quiet })
    if ($strayNs.Count -gt 0) { $strayNs | Add-Content $log }
    try {
        $gitleaks = (& scripts/ensure-gitleaks.ps1 | Select-Object -Last 1)
        $archive = Join-Path $root "build/gitleaks-tracked-head.zip"
        & git archive --format=zip -o $archive HEAD
        if ($LASTEXITCODE -ne 0) { throw "git archive failed" }
        $out = & $gitleaks dir --no-banner --no-color --redact=100 --max-archive-depth=1 --max-decode-depth=2 $archive 2>&1
        $scanCode = $LASTEXITCODE
        $out | Add-Content $log
    } catch {
        $_ | Add-Content $log
        return "FAIL: Gitleaks could not run — see build/sweep.log"
    }
    if ($ignoredTracked.Count -eq 0 -and $scanCode -eq 0 -and $strayNs.Count -eq 0) { return "PASS" }
    return "FAIL: ignored-tracked=$($ignoredTracked.Count) gitleaks-exit=$scanCode android-xmlns-outside-res=$($strayNs.Count) — see build/sweep.log (findings are redacted)"
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

& $buildGates

Gate "5b. Installed-app loops — all shipped verifiers" {
    if ($DocsOnly) { return "SKIPPED (docs-only)" }
    if ($SkipConnected) { return "SKIPPED (-SkipConnected; onboarding is not verified)" }
    if ($loopStartError) { return "FAIL: could not start runner: $loopStartError" }
    if ($null -eq $loopProc) {
        return "SKIPPED (debug APK missing or assemble did not pass — onboarding never started)"
    }
    $loopProc | Wait-Process
    $code = $loopProc.ExitCode
    if (Test-Path $loopLog) { Get-Content $loopLog | Add-Content $log }
    if (Test-Path $loopErr) { Get-Content $loopErr | Add-Content $log }
    $tail = ""
    if (Test-Path $loopLog) { $tail = (@(Get-Content $loopLog) | Select-Object -Last 1) }
    if (Test-Path -LiteralPath $loopReceiptPath) {
        $script:loopReceipt = Get-Content -LiteralPath $loopReceiptPath -Raw | ConvertFrom-Json
    }
    $expected = @(Get-ChildItem .cursor/skills/verify-openloop/helpers/*_loop.py | ForEach-Object { $_.BaseName -replace '_loop$', '' } | Sort-Object)
    $currentApk = $artifacts["app/build/outputs/apk/debug/app-debug.apk"]
    $complete = Test-FullLoopReceipt $loopReceipt $expected $currentApk
    if ($code -eq 0 -and "$tail" -eq "PASS loops=$($expected -join ',')" -and $complete) { return "PASS ($tail; all executed)" }
    return "FAIL: exit=$code complete=$complete selected=$($loopReceipt.selected -join ',') expected=$($expected -join ',') final=$tail"
}

if (-not $DocsOnly) {
    Gate "5. Instrumented tests — 0 failures" {
        if ($SkipConnected) { return "SKIPPED (-SkipConnected; run connectedDebugAndroidTest before the PR)" }
        if (-not $loopReceipt.serial) { return "FAIL: no device serial established by the installed-app runner" }
        if ($loopReceipt.serial -notmatch '^emulator-\d+$' -and $env:VERIFY_ALLOW_DEVICE -ne '1') { return "FAIL: physical device not authorized for verification" }
        if ($env:ANDROID_SERIAL -and $env:ANDROID_SERIAL -ne $loopReceipt.serial) { return "FAIL: runner serial differs from ANDROID_SERIAL" }
        $env:ANDROID_SERIAL = $loopReceipt.serial
        Remove-ReportDirectory (Join-Path $root "app/build/outputs/androidTest-results/connected")
        $r = Run-Gradle @(":app:connectedDebugAndroidTest")
        $s = Sum-JUnit "app/build/outputs/androidTest-results/connected"
        $s.Execution = $gradleTasks[":app:connectedDebugAndroidTest"]
        $script:testResults["connected"] = $s
        if ($r.Code -eq 0 -and $s.Tests -gt 0 -and $s.Failures -eq 0 -and $s.Errors -eq 0 -and $s.Execution -eq "EXECUTED") {
            return "PASS ($($s.Tests) tests, $($s.Skipped) skipped, 0 failures, 0 errors; EXECUTED)"
        }
        return "FAIL: exit=$($r.Code) tests=$($s.Tests) failures=$($s.Failures) errors=$($s.Errors)"
    }
}

Gate "10. Artifact and source identity" {
    if ((git rev-parse HEAD).Trim() -ne $headAtStart) { return "FAIL: HEAD changed during the sweep" }
    if ($loopReceipt -and $loopReceipt.sourceSha -ne $headAtStart) { return "FAIL: installed-app receipt belongs to a different commit" }
    foreach ($path in $artifacts.Keys) {
        if (-not (Test-Path -LiteralPath $path)) { return "FAIL: artifact disappeared: $path" }
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $artifacts[$path]) {
            return "FAIL: artifact changed during the sweep: $path"
        }
    }
    return "PASS ($($artifacts.Count) APK hashes unchanged; same HEAD)"
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
$dirty = @(git status --porcelain)
$loopVerdict = $results["5b. Installed-app loops — all shipped verifiers"]
$receipt = [ordered]@{
    sha                = $sha
    branch             = (git branch --show-current).Trim()
    at                 = (Get-Date -Format o)
    treeClean          = ($dirtyAtStart.Count -eq 0 -and $dirty.Count -eq 0)
    docsOnly           = [bool]$DocsOnly
    cleanBuild         = [bool]$Clean
    inspectCode        = $inspect
    connected          = (-not $SkipConnected) -and (-not $DocsOnly)
    onboardingLoop     = if ($loopVerdict -like "PASS*") { "passed" } elseif ($loopVerdict -like "SKIPPED*") { "skipped" } else { "failed" }
    gates              = $results
    durationSec        = $timings
    gradleTasks        = $gradleTasks
    tests              = $testResults
    artifacts          = $artifacts
    verificationLoops  = $loopReceipt
    rerunTests         = [bool]$RerunTests
    verificationBuild  = (-not $DocsOnly)
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
    Write-Host "SWEEP GREEN for $sha — but the tree has uncommitted changes; commit, then re-run so the receipt matches HEAD." -ForegroundColor Yellow
} else {
    Write-Host "SWEEP GREEN for $sha — receipt: build/sweep-receipt.json (inspectCode=$inspect)" -ForegroundColor Green
}
exit 0
