<#
.SYNOPSIS
  Phase 2 of the pixel-sweep: drive the full OpenLoop flow on an already-prepped emulator —
  onboarding → Gallery → import the canonical video → trim → speed 1x → ALL four loop
  directions (reverse preview verified via logcat) → B&W look → Save (render worker verified)
  → share sheet → gallery playback. Every gesture is computed from uiautomator bounds, so the
  same script works on any screen size (1080×2400 phones and the 2076×2152 unfolded Fold alike).

.USAGE
  pwsh drive-flow.ps1 -Serial emulator-5554 -ArtifactDir $env:TEMP\openloop_sweep\pixel6 [-Fold]
  # -Fold: additionally toggles posture (fold → unfold) while the editor preview is up.
  # Exit 0 = every step PASS. Exit 2 = a step failed (read the [FAIL] line + flow-summary.txt).

.NOTES
  Writes $ArtifactDir\flow-summary.txt with key=value pairs the quality gate consumes
  (boomName, expectedSeconds). The logcat capture must already be streaming to
  $ArtifactDir\logcat.txt (sweep-prep.ps1 does this).
#>
param(
  [Parameter(Mandatory = $true)][string]$Serial,
  [Parameter(Mandatory = $true)][string]$ArtifactDir,
  [switch]$Fold
)
$ErrorActionPreference = 'Stop'
$LogFile = Join-Path $ArtifactDir "logcat.txt"
$Summary = Join-Path $ArtifactDir "flow-summary.txt"
Set-Content $Summary ""

# ── UI helpers (bounds-aware; matching against text AND content-desc, entities decoded) ──────
function Decode([string]$t) {
  if (-not $t) { return $t }
  $t.Replace('&amp;', '&').Replace('&lt;', '<').Replace('&gt;', '>').Replace('&quot;', '"').Replace('&#39;', "'")
}
function Get-Nodes {
  $null = adb -s $Serial shell uiautomator dump /sdcard/ui.xml 2>$null
  $xml = adb -s $Serial shell cat /sdcard/ui.xml
  [regex]::Matches($xml, '<node[^>]*>') | ForEach-Object {
    $n = $_.Value
    $bm = [regex]::Match($n, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($bm.Success) {
      [pscustomobject]@{
        Text = Decode ([regex]::Match($n, 'text="([^"]*)"').Groups[1].Value)
        Desc = Decode ([regex]::Match($n, 'content-desc="([^"]*)"').Groups[1].Value)
        X1 = [int]$bm.Groups[1].Value; Y1 = [int]$bm.Groups[2].Value
        X2 = [int]$bm.Groups[3].Value; Y2 = [int]$bm.Groups[4].Value
      }
    }
  }
}
function Find-Node($nodes, [string]$Label, [switch]$Exact) {
  if ($Exact) { $nodes | Where-Object { $_.Text -eq $Label -or $_.Desc -eq $Label } | Select-Object -First 1 }
  else { $nodes | Where-Object { $_.Text -like "*$Label*" -or $_.Desc -like "*$Label*" } | Select-Object -First 1 }
}
function Tap-Node($node) {
  $cx = [int](($node.X1 + $node.X2) / 2); $cy = [int](($node.Y1 + $node.Y2) / 2)
  adb -s $Serial shell input tap $cx $cy | Out-Null
}
# Tap by label with retries; -Exact prefers an exact text/desc match (CRITICAL for the
# notifications dialog, where substring "Allow" first matches the question text, not the button).
function Tap([string]$Label, [int]$Retries = 3, [switch]$Exact) {
  for ($i = 0; $i -lt $Retries; $i++) {
    $n = Find-Node (Get-Nodes) $Label -Exact:$Exact
    if ($n) { Tap-Node $n; return $true }
    Start-Sleep -Seconds 2
  }
  return $false
}
function Wait-LogPattern([string]$Pattern, [int]$TimeoutSec) {
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  do {
    $hit = Select-String -Path $LogFile -Pattern $Pattern -ErrorAction SilentlyContinue | Select-Object -Last 1
    if ($hit) { return $hit.Line }
    Start-Sleep -Seconds 5
  } while ((Get-Date) -lt $deadline)
  return $null
}
function Get-TrimLabel {
  $n = (Get-Nodes) | Where-Object { $_.Text -match '\d\d:\d\d\.\d' } | Select-Object -First 1
  if ($n) { $n.Text } else { $null }
}
function Get-OutputSeconds {  # the editor's predicted output-duration chip, e.g. "7.7s"
  $n = (Get-Nodes) | Where-Object { $_.Text -match '^\d+(\.\d)?s$' } | Select-Object -First 1
  if ($n) { [double]($n.Text.TrimEnd('s')) } else { $null }
}

$script:failed = $false
function Step([string]$Name, [scriptblock]$Body) {
  if ($script:failed) { return }
  try {
    $ok = & $Body
    if ($ok -is [array]) { $ok = $ok[-1] }   # scriptblocks can emit pipeline noise; verdict is last
    if ($ok) { Write-Host "[PASS] $Name"; Add-Content $Summary "step.$($Name -replace '\s','_')=PASS" }
    else { Write-Host "[FAIL] $Name"; Add-Content $Summary "step.$($Name -replace '\s','_')=FAIL"; $script:failed = $true }
  } catch {
    Write-Host "[FAIL] $Name — $($_.Exception.Message)"
    Add-Content $Summary "step.$($Name -replace '\s','_')=FAIL"
    $script:failed = $true
  }
}

# ── 0. Onboarding (fresh pm-clear state shows it; LET'S GO! lands on the camera).
#       Self-heal: a slow cold boot (the Fold especially) can swallow sweep-prep's am start —
#       if neither the onboarding nor the camera is visible, re-launch the activity. ─────────
Step "onboarding" {
  for ($i = 0; $i -lt 6; $i++) {
    $nodes = Get-Nodes
    if (Find-Node $nodes "Start recording") { return $true }
    if (Find-Node $nodes "LET'S GO!") { Tap "LET'S GO!" | Out-Null; Start-Sleep -Seconds 3 }
    else {
      adb -s $Serial shell am start -n "io.github.stozo04.openloop/.MainActivity" | Out-Null
      Start-Sleep -Seconds 4
    }
  }
  [bool](Find-Node (Get-Nodes) "Start recording")
}

# ── 1. Import: Gallery → photo picker → canonical video → Done → Trim screen ─────────────────
Step "import" {
  if (-not (Tap "Gallery")) { return $false }
  Start-Sleep -Seconds 2
  if (-not (Tap "Import a video")) { return $false }
  Start-Sleep -Seconds 4
  if (-not (Tap "Video taken on")) { return $false }   # the pushed clip in the system photo picker
  Start-Sleep -Seconds 2
  if (-not (Tap "Done")) { return $false }
  Start-Sleep -Seconds 6
  [bool](Find-Node (Get-Nodes) "TRIM YOUR VIDEO")
}

# ── 2. Trim: drag the end handle left ~30% of screen width, slowly (fast swipes are flings) ──
Step "trim" {
  $before = Get-TrimLabel
  $end = Find-Node (Get-Nodes) "Trim end"
  if (-not $end) { return $false }
  $size = (adb -s $Serial shell wm size) -join ''
  $w = [int]([regex]::Match($size, '(\d+)x\d+').Groups[1].Value)
  $hx = [int](($end.X1 + $end.X2) / 2); $hy = [int](($end.Y1 + $end.Y2) / 2)
  adb -s $Serial shell input swipe $hx $hy ([int]($hx - 0.3 * $w)) $hy 1300 | Out-Null
  Start-Sleep -Seconds 2
  $after = Get-TrimLabel
  Add-Content $Summary "trim.before=$before"; Add-Content $Summary "trim.after=$after"
  ($after) -and ($after -ne $before)
}

# ── 3. Speed → exactly 1x. Continuous SeekBar: tap slightly right of the "1x" tick label,
#       verify the value next to "Current speed", retry with growing offsets. ─────────────────
Step "speed 1x" {
  if (-not (Tap "Speed")) { return $false }
  Start-Sleep -Seconds 2
  $nodes = Get-Nodes
  $bar = Find-Node $nodes "Playback speed"
  if (-not $bar) { return $false }
  $barY = [int](($bar.Y1 + $bar.Y2) / 2)
  # the "1x" TICK label sits just below the bar (distinct from the current-speed VALUE chip)
  $tick = $nodes | Where-Object { $_.Text -eq '1x' -and $_.Y1 -ge $bar.Y2 -and $_.Y1 -lt ($bar.Y2 + 150) } | Select-Object -First 1
  if (-not $tick) { return $false }
  $tickX = [int](($tick.X1 + $tick.X2) / 2)
  $barW = $bar.X2 - $bar.X1
  foreach ($frac in @(0.039, 0.026, 0.052, 0.013, 0.065)) {   # ≈35px on a 1080-wide bar, then hunt
    adb -s $Serial shell input tap ([int]($tickX + $frac * $barW)) $barY | Out-Null
    Start-Sleep -Seconds 1
    $n2 = Get-Nodes
    $cur = Find-Node $n2 "Current speed"
    $val = $n2 | Where-Object { $_.Text -match '^[\d.]+x$' -and $cur -and [math]::Abs($_.Y1 - $cur.Y1) -lt 30 } | Select-Object -First 1
    if ($val -and $val.Text -eq '1x') { Add-Content $Summary "speed=1x"; return $true }
  }
  if ($val) { Add-Content $Summary "speed=$($val.Text)" }  # speed DID change but not exactly 1x
  return $false
}

# ── 4. Loop tab → reverse-needing direction; the reverse preview must complete (NOT time out).
#       Normal: ~3-5 s on these emulators. Budget the full 120 s before calling it stalled. ──
Step "reverse preview" {
  if (-not (Tap "Loop")) { return $false }
  Start-Sleep -Seconds 2
  if (-not (Tap "Reverse then forward")) { return $false }
  $line = Wait-LogPattern "viewModel\.ensureReversed\.(ok|timeout|fail)" 130
  Add-Content $Summary "reverse.terminal=$line"
  ($line) -and ($line -match "ensureReversed\.ok")
}

# ── 4b. Fold only: posture toggle mid-preview. Folding LOCKS the emulator screen; the
#        unfold needs the wakeup + dismiss-keyguard dance before the UI is reachable. ────────
if ($Fold) {
  Step "fold-unfold posture" {
    adb -s $Serial emu fold | Out-Null; Start-Sleep -Seconds 5
    adb -s $Serial emu unfold | Out-Null; Start-Sleep -Seconds 4
    adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null; Start-Sleep -Seconds 1
    adb -s $Serial shell wm dismiss-keyguard | Out-Null; Start-Sleep -Seconds 2
    adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null; Start-Sleep -Seconds 2
    $crash = Select-String -Path $LogFile -Pattern "FATAL EXCEPTION|CodecException|surface has been released|ANR in" -ErrorAction SilentlyContinue
    if ($crash) { return $false }
    [bool](Find-Node (Get-Nodes) "Save boomerang")   # editor survived the display switch
  }
}

# ── 5. Every direction option must select cleanly: no fallback/error, sane duration chip.
#       Sanity: then-modes ≈ 2× single modes (ping-pong doubles the trimmed window at 1x). ───
Step "all directions" {
  $durs = @{}
  foreach ($d in @("Forward loop", "Reverse loop", "Forward then reverse", "Reverse then forward")) {
    if (-not (Tap $d)) { return $false }
    Start-Sleep -Seconds 2
    $nodes = Get-Nodes
    foreach ($bad in @("forward only", "unavailable", "failed", "Retry")) {
      if (Find-Node $nodes $bad) { Add-Content $Summary "direction.error=$d -> $bad"; return $false }
    }
    $durs[$d] = Get-OutputSeconds
    Add-Content $Summary "direction.$($d -replace '\s','_')=$($durs[$d])s"
  }
  $ratio = $durs["Reverse then forward"] / $durs["Forward loop"]
  Add-Content $Summary "direction.pingpong_ratio=$([math]::Round($ratio,2))"
  Add-Content $Summary "expectedSeconds=$($durs['Reverse then forward'])"
  ($ratio -gt 1.8) -and ($ratio -lt 2.2)   # leaves Reverse-then-forward selected for the save
}

# ── 6. Looks: a non-Original filter must apply without a "Preview unavailable" banner ────────
Step "B&W look" {
  if (-not (Tap "Filter")) { return $false }
  Start-Sleep -Seconds 2
  if (-not (Tap "B&W")) { return $false }
  Start-Sleep -Seconds 2
  -not (Find-Node (Get-Nodes) "unavailable")
}

# ── 7. Save: render worker must report SUCCESS; share sheet must show the boom_*.mp4.
#       Fresh installs interpose the POST_NOTIFICATIONS dialog — tap the exact "Allow". ──────
Step "save + share sheet" {
  if (-not (Tap "Save boomerang")) { return $false }
  $line = Wait-LogPattern "Worker result (SUCCESS|FAILURE) for Work .*BoomerangRenderWorker" 150
  Add-Content $Summary "render.worker=$line"
  if (-not $line -or $line -notmatch "SUCCESS") { return $false }
  Start-Sleep -Seconds 2
  $nodes = Get-Nodes
  if (Find-Node $nodes "Allow OpenLoop to send you notifications") {
    Tap "Allow" -Exact | Out-Null            # exact match — substring would hit the question text
    Start-Sleep -Seconds 2
    $nodes = Get-Nodes
  }
  $boom = $nodes | Where-Object { $_.Text -match '^boom_.*\.mp4$' } | Select-Object -First 1
  if (-not $boom) { return $false }
  Add-Content $Summary "boomName=$($boom.Text)"
  adb -s $Serial shell input keyevent BACK | Out-Null   # dismiss the share sheet → gallery
  Start-Sleep -Seconds 2
  return $true
}

# ── 8. Playback: gallery grid is newest-first; the first tile is the clip just saved ─────────
Step "gallery playback" {
  $thumb = (Get-Nodes) | Where-Object { $_.Desc -eq "Video thumbnail" } | Select-Object -First 1
  if (-not $thumb) { return $false }
  Tap-Node $thumb
  Start-Sleep -Seconds 5
  $nodes = Get-Nodes
  if (-not (Find-Node $nodes "Close preview")) { return $false }
  if (Select-String -Path $LogFile -Pattern "Playback error" -ErrorAction SilentlyContinue) { return $false }
  Tap "Close preview" | Out-Null
  return $true
}

if ($script:failed) {
  Write-Host "`nFLOW FAILED — see [FAIL] step above and $Summary"
  exit 2
}
Write-Host "`nFLOW COMPLETE — all steps PASS. Summary: $Summary"
exit 0
