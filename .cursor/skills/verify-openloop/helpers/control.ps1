<#
.SYNOPSIS
  Launch, doctor, dump, tap, and clean up an OpenLoop verification run.

.USAGE
  pwsh helpers/control.ps1 doctor
  pwsh helpers/control.ps1 launch
  pwsh helpers/control.ps1 dump
  pwsh helpers/control.ps1 tap -Label "Start recording"
  pwsh helpers/control.ps1 cleanup
  pwsh helpers/control.ps1 evidence-dir

  Optional: -Serial emulator-5554
  VERIFY_SERIAL, VERIFY_ALLOW_DEVICE=1, VERIFY_EVIDENCE_DIR, JAVA_HOME
#>
param(
  [Parameter(Mandatory = $true)]
  [ValidateSet('doctor', 'launch', 'dump', 'tap', 'cleanup', 'evidence-dir', 'grant-camera')]
  [string]$Action,
  [string]$Label,
  [string]$Serial
)

$ErrorActionPreference = 'Stop'
$Package = 'io.github.stozo04.openloop'
$Activity = 'io.github.stozo04.openloop/.MainActivity'
$SkillDir = Split-Path -Parent $PSScriptRoot
$RepoRoot = (Resolve-Path (Join-Path $SkillDir '..\..\..')).Path
$Uiauto = Join-Path $RepoRoot '.claude\skills\run-e2e\scripts\uiauto.ps1'
$Apk = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'

function Get-Adb {
  $cmd = Get-Command adb -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
  if (Test-Path $sdk) { return $sdk }
  throw 'adb not found. Install platform-tools or put adb on PATH.'
}

function Get-EvidenceDir {
  if ($env:VERIFY_EVIDENCE_DIR) { return $env:VERIFY_EVIDENCE_DIR }
  $run = if ($env:VERIFY_RUN_ID) { $env:VERIFY_RUN_ID } else { Get-Date -Format 'yyyyMMdd_HHmmss' }
  return Join-Path $env:TEMP "openloop-verify\$run"
}

function Resolve-Serial([string]$s) {
  if ($s) { return $s }
  if ($env:VERIFY_SERIAL) { return $env:VERIFY_SERIAL }
  $adb = Get-Adb
  $lines = & $adb devices
  $emus = @($lines | Where-Object { $_ -match 'emulator-\d+\s+device' } | ForEach-Object { ($_ -split '\s+')[0] })
  if ($emus.Count -eq 1) { return $emus[0] }
  if ($emus.Count -gt 1) { throw "Multiple emulators: $($emus -join ', '). Set VERIFY_SERIAL." }
  $devs = @($lines | Where-Object { $_ -match '\s+device$' -and $_ -notmatch 'emulator-' } | ForEach-Object { ($_ -split '\s+')[0] })
  if ($devs.Count -ge 1) {
    if ($env:VERIFY_ALLOW_DEVICE -eq '1') { return $devs[0] }
    throw "Physical device $($devs[0]) attached. Refusing to drive it. Start an emulator, or set VERIFY_ALLOW_DEVICE=1 and VERIFY_SERIAL."
  }
  throw 'No emulator or device (adb devices). Start an AVD first.'
}

$Evidence = Get-EvidenceDir
if ($Action -eq 'evidence-dir') {
  New-Item -ItemType Directory -Force -Path $Evidence | Out-Null
  Write-Output $Evidence
  return
}

$Adb = Get-Adb
$Serial = Resolve-Serial $Serial

switch ($Action) {
  'doctor' {
    $state = & $Adb -s $Serial get-state
    if ($state.Trim() -ne 'device') { throw "doctor: serial $Serial state=$state" }
    if ($Serial -notmatch '^emulator-' -and $env:VERIFY_ALLOW_DEVICE -ne '1') {
      throw "doctor: $Serial is not an emulator. Set VERIFY_ALLOW_DEVICE=1 only for a dedicated test phone."
    }
    $path = & $Adb -s $Serial shell pm path $Package
    if (-not ($path -match 'package:')) { throw "doctor: $Package is not installed on $Serial" }
    $ver = & $Adb -s $Serial shell dumpsys package $Package
    $name = ($ver | Select-String 'versionName=' | Select-Object -First 1).ToString().Trim()
    $code = ($ver | Select-String 'versionCode=' | Select-Object -First 1).ToString().Trim()
    $pid = (& $Adb -s $Serial shell pidof $Package 2>$null)
    Write-Output "doctor: ok serial=$Serial $name $code pid=$pid apk=$path"
    break
  }
  'grant-camera' {
    & $Adb -s $Serial shell pm grant $Package android.permission.CAMERA
    Write-Output "granted CAMERA on $Serial"
    break
  }
  'launch' {
    New-Item -ItemType Directory -Force -Path $Evidence | Out-Null
    if (-not (Test-Path $Apk)) {
      if (-not $env:JAVA_HOME) {
        $jbr = 'C:\Program Files\Android\Android Studio\jbr'
        if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
      }
      $gradlew = Join-Path $RepoRoot 'gradlew.bat'
      & $gradlew -p $RepoRoot :app:assembleDebug --console=plain
      if ($LASTEXITCODE -ne 0) { throw 'assembleDebug failed' }
      if (-not (Test-Path $Apk)) { throw "APK missing after build: $Apk" }
    }
    & $Adb -s $Serial install -r -g $Apk
    if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }
    & $Adb -s $Serial shell pm grant $Package android.permission.CAMERA
    & $Adb -s $Serial shell am start -n $Activity
    Start-Sleep -Seconds 2
    pwsh $PSCommandPath doctor -Serial $Serial
    break
  }
  'dump' {
    if (-not (Test-Path $Uiauto)) { throw "missing $Uiauto" }
    & pwsh $Uiauto -Action dump -Serial $Serial
    break
  }
  'tap' {
    if (-not $Label) { throw '-Label is required for tap' }
    if (-not (Test-Path $Uiauto)) { throw "missing $Uiauto" }
    & pwsh $Uiauto -Action tap -Label $Label -Serial $Serial
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    break
  }
  'cleanup' {
    & $Adb -s $Serial shell am force-stop $Package
    Write-Output "cleanup: force-stopped $Package on $Serial; evidence kept at $Evidence"
    break
  }
}
