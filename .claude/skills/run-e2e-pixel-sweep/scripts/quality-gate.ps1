<#
.SYNOPSIS
  Phase 3 of the pixel-sweep: pull the freshly saved boom_*.mp4 off the device (binary-safe)
  and prove it is a genuine, clean boomerang — structure, duration vs the editor's prediction,
  PER-HALF frame rate (the check that catches a subsampled/halved reverse half), mirror-point
  SSIM, freeze detection, and green/black-frame scans.

.USAGE
  pwsh quality-gate.ps1 -Serial emulator-5554 -ArtifactDir $env:TEMP\openloop_sweep\pixel6
  # Reads boomName + expectedSeconds from $ArtifactDir\flow-summary.txt (drive-flow.ps1 wrote it).
  # Exit 0 = all checks PASS (WARNs allowed — eyeball the frames). Exit 2 = a check failed.

.NOTES
  - ffmpeg/ffprobe must be on PATH (winget install Gyan.FFmpeg).
  - Pull method matters: `run-as cat > /sdcard/...` writes 0 bytes (run-as can't write there)
    and PowerShell pipes mangle binary — `cmd /c adb exec-out run-as ... cat > file` is the
    only reliable one-liner.
  - The B&W look makes U=V=128 by design — flat chroma here is the FILTER WORKING, not
    corruption. Green corruption is U AND V well BELOW neutral (≈<110) on many frames.
#>
param(
  [Parameter(Mandatory = $true)][string]$Serial,
  [Parameter(Mandatory = $true)][string]$ArtifactDir
)
$ErrorActionPreference = 'Stop'
$pkg = "io.github.stozo04.openloop"
$Summary = Join-Path $ArtifactDir "flow-summary.txt"
$kv = @{}
Get-Content $Summary | ForEach-Object { if ($_ -match '^([^=]+)=(.*)$') { $kv[$Matches[1]] = $Matches[2] } }
$boom = $kv['boomName']
$expected = [double]$kv['expectedSeconds']
if (-not $boom) { Write-Error "flow-summary.txt has no boomName — did drive-flow.ps1 pass?"; exit 1 }

$out = Join-Path $ArtifactDir "boom.mp4"
cmd /c "adb -s $Serial exec-out run-as $pkg cat files/videos/$boom > `"$out`""
if ((Get-Item $out).Length -lt 10000) { Write-Error "Pulled file suspiciously small ($((Get-Item $out).Length) B) — pull failed?"; exit 1 }

$script:fails = 0
function Check([string]$Name, [bool]$Ok, [string]$Detail) {
  $verdict = if ($Ok) { "PASS" } else { $script:fails++; "FAIL" }
  Write-Host ("[{0}] {1,-28} {2}" -f $verdict, $Name, $Detail)
}

# ── Structure: exactly one H.264 video track at the source resolution ────────────────────────
$streams = ffprobe -v error -show_entries "stream=codec_type,codec_name,width,height" -of csv $out 2>$null
$vstreams = @($streams | Select-String "video")
Check "one video track" ($vstreams.Count -eq 1) ($vstreams -join ' | ')
Check "H.264" ($vstreams[0] -match "h264") $vstreams[0]

# ── Duration vs the editor's predicted output (trim × speed × direction) ─────────────────────
$dur = [double](ffprobe -v error -show_entries format=duration -of csv=p=0 $out 2>$null)
Check "duration ≈ editor prediction" ([math]::Abs($dur - $expected) -le 0.4) ("file=$([math]::Round($dur,2))s editor=$($expected)s")

# ── Per-half frame rate: the sharp regression check for the pass-1 subsample bug. A healthy
#    30 fps source gives ~30 fps in BOTH halves; the bug signature was 15 fps in the reversed
#    half only (fold-loop BUG-1). ─────────────────────────────────────────────────────────────
$pts = ffprobe -v error -select_streams v:0 -show_entries frame=pts_time -of csv=p=0 $out 2>$null | ForEach-Object { [double]($_.TrimEnd(',')) }
$half = $dur / 2
$h1 = @($pts | Where-Object { $_ -lt $half }).Count / $half
$h2 = @($pts | Where-Object { $_ -ge $half }).Count / $half
Check "first-half fps ≥ 27" ($h1 -ge 27) ("$([math]::Round($h1,1)) fps")
Check "second-half fps ≥ 27" ($h2 -ge 27) ("$([math]::Round($h2,1)) fps")

# ── Mirror-point frames: for *_THEN_* modes, 25% and 75% are the same source frame; 0% and
#    ~100% match too. SSIM ≥0.90 expected (healthy runs: 0.93–0.96); 0.85–0.90 is borderline —
#    WARN, eyeball the frames; <0.85 fails. The iter-1 CORRUPT half still scored 0.88, so do
#    not trust SSIM alone — the per-half fps + visual check carry the real weight. ───────────
$fdir = Join-Path $ArtifactDir "frames"
New-Item -ItemType Directory -Force $fdir | Out-Null
foreach ($p in @(0, 25, 50, 75, 99)) {
  $t = [math]::Round($dur * $p / 100, 3)
  ffmpeg -v error -ss $t -i $out -frames:v 1 -y (Join-Path $fdir "f$p.png") 2>$null
}
function Ssim($a, $b) {
  $line = ffmpeg -i (Join-Path $fdir $a) -i (Join-Path $fdir $b) -lavfi ssim -f null - 2>&1 | Select-String "All:"
  if ($line -and $line.Line -match 'All:([\d.]+)') { [double]$Matches[1] } else { 0 }
}
$s2575 = Ssim "f25.png" "f75.png"
$s0100 = Ssim "f0.png" "f99.png"
Check "mirror SSIM 25↔75 ≥ 0.85" ($s2575 -ge 0.85) ("$s2575 $(if ($s2575 -lt 0.90) { '— BORDERLINE: eyeball frames/f25+f75, send to user if unsure' })")
Check "ends SSIM 0↔99 ≥ 0.85" ($s0100 -ge 0.85) ("$s0100")

# ── Freeze / stutter ─────────────────────────────────────────────────────────────────────────
$freeze = ffmpeg -i $out -vf "freezedetect=n=-60dB:d=0.5" -f null - 2>&1 | Select-String "freeze_start"
Check "no frozen runs ≥ 0.5s" (-not $freeze) ($(if ($freeze) { $freeze[0].Line } else { "none" }))

# ── Green / black frame scan (lesson 021's SW-path corruption signature) ─────────────────────
$stats = ffmpeg -i $out -vf "signalstats,metadata=print:file=-" -f null - 2>$null | Select-String "YAVG|UAVG|VAVG"
$y = @(); $u = @(); $v = @()
foreach ($s in $stats) {
  if ($s -match "YAVG=([\d.]+)") { $y += [double]$Matches[1] }
  if ($s -match "UAVG=([\d.]+)") { $u += [double]$Matches[1] }
  if ($s -match "VAVG=([\d.]+)") { $v += [double]$Matches[1] }
}
$black = @($y | Where-Object { $_ -lt 20 }).Count
$green = 0
for ($i = 0; $i -lt [math]::Min($u.Count, $v.Count); $i++) { if ($u[$i] -lt 110 -and $v[$i] -lt 110) { $green++ } }
Check "no near-black frames" ($black -eq 0) ("$black of $($y.Count) frames YAVG<20")
Check "no green-shifted frames" ($green -le [int](0.02 * $u.Count)) ("$green frames U&V<110 (B&W look ⇒ U=V=128 is CORRECT, not a failure)")

Write-Host ""
if ($script:fails -gt 0) { Write-Host "QUALITY GATE FAILED ($script:fails checks) — frames in $fdir"; exit 2 }
Write-Host "QUALITY GATE PASS — boom=$boom ($([math]::Round($dur,2))s, $($pts.Count) frames). Frames for eyeballing: $fdir"
exit 0
