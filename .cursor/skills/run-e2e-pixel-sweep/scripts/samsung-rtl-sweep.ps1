<#
.SYNOPSIS
  Full Samsung Remote Test Lab (RTL) regression: prep → drive-flow → quality-gate → logcat scan.

.USAGE
  # 1. Reserve a Galaxy on Samsung RTL, start RDB, connect in browser (see docs/guides/samsung-rtl-steps.md)
  # 2. Build once:
  #      $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  #      .\gradlew.bat :app:assembleDebug
  # 3. Run:
  pwsh samsung-rtl-sweep.ps1 -ArtifactDir $env:TEMP\openloop_rtl\s23
  #    Optional: -Serial localhost:52172  (auto-detects first localhost:* if omitted)

.NOTES
  Reuses drive-flow.ps1, quality-gate.ps1, and scan-logcat.ps1 from this skill + run-e2e.
  Keep the RTL browser tab awake — debugging drops when the remote screen sleeps.
#>
param(
  [string]$Serial,
  [Parameter(Mandatory = $true)][string]$ArtifactDir,
  [string]$RepoRoot = "C:\Users\gates\Personal\OpenRang",
  [switch]$SkipQualityGate
)
$ErrorActionPreference = 'Stop'
$skill = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoSkill = Join-Path $RepoRoot ".claude\skills\run-e2e-pixel-sweep\scripts"
$e2eSkill = Join-Path $RepoRoot ".claude\skills\run-e2e\scripts"

foreach ($dir in @($skill, $repoSkill, $e2eSkill)) {
  if (-not (Test-Path $dir)) { Write-Error "Skill scripts missing: $dir"; exit 1 }
}

Write-Host "=== Samsung RTL sweep ==="
& (Join-Path $skill "samsung-rtl-prep.ps1") -Serial $Serial -ArtifactDir $ArtifactDir -RepoRoot $RepoRoot
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$info = Get-Content (Join-Path $ArtifactDir "device-info.txt") -ErrorAction SilentlyContinue
$serialLine = $info | Where-Object { $_ -like 'serial=*' } | Select-Object -First 1
if ($serialLine) { $Serial = ($serialLine -split '=', 2)[1] }
if (-not $Serial) { Write-Error "Prep did not yield a serial."; exit 1 }

Write-Host "=== drive-flow on $Serial ==="
& (Join-Path $skill "drive-flow.ps1") -Serial $Serial -ArtifactDir $ArtifactDir
$flowExit = $LASTEXITCODE

if (-not $SkipQualityGate) {
  Write-Host "=== quality-gate ==="
  & (Join-Path $skill "quality-gate.ps1") -Serial $Serial -ArtifactDir $ArtifactDir
  if ($LASTEXITCODE -ne 0) { $flowExit = [Math]::Max($flowExit, $LASTEXITCODE) }
}

Write-Host "=== scan-logcat ==="
& (Join-Path $e2eSkill "scan-logcat.ps1") -LogFile (Join-Path $ArtifactDir "logcat.txt")

Write-Host "=== RTL sweep complete (flow exit=$flowExit) ==="
Write-Host "Artifacts: $ArtifactDir"
exit $flowExit
