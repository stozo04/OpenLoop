<#
.SYNOPSIS
  Prep a Samsung Remote Test Lab (RTL) device for the pixel-sweep flow — install debug APK,
  fresh app state, push canonical test video, 3-button nav, logcat capture, launch app.

.USAGE
  # After RDB connect (adb devices shows localhost:<port>):
  pwsh samsung-rtl-prep.ps1 -Serial localhost:52172 -ArtifactDir $env:TEMP\openloop_rtl\s23

  Auto-detects the first localhost:* serial when -Serial is omitted.

.NOTES
  RTL devices are REAL Galaxy hardware — not emulators. See docs/guides/samsung-rtl-steps.md.
  Does NOT start an emulator. Refuses non-Samsung manufacturers (LGE, Google, etc.).
#>
param(
  [string]$Serial,
  [Parameter(Mandatory = $true)][string]$ArtifactDir,
  [string]$RepoRoot = "C:\Users\gates\Personal\OpenRang",
  [string]$VideoName = "google-pro-fold-video.mp4"
)
$ErrorActionPreference = 'Stop'
$pkg = "io.github.stozo04.openloop"
$video = Join-Path $RepoRoot $VideoName
$apk = Join-Path $RepoRoot "app\build\outputs\apk\debug\app-debug.apk"

if (-not (Test-Path $video)) { Write-Error "Canonical test video missing: $video — STOP, do not substitute."; exit 1 }
if (-not (Test-Path $apk)) { Write-Error "APK not built: $apk — run :app:assembleDebug first."; exit 1 }
New-Item -ItemType Directory -Force $ArtifactDir | Out-Null

if (-not $Serial) {
  $line = (adb devices) | Select-String 'localhost:\d+\s+device' | Select-Object -First 1
  if (-not $line) { Write-Error "No RTL device (localhost:<port>) in adb devices. Connect RDB first — see docs/guides/samsung-rtl-steps.md"; exit 1 }
  $Serial = ($line.ToString() -split '\s+')[0]
}

$state = (adb devices) | Select-String "$([regex]::Escape($Serial))\s+device"
if (-not $state) { Write-Error "Serial '$Serial' not in 'device' state. Run adb devices."; exit 1 }

$manufacturer = ((adb -s $Serial shell getprop ro.product.manufacturer) -join '').Trim()
$model = ((adb -s $Serial shell getprop ro.product.model) -join '').Trim()
$sdk = ((adb -s $Serial shell getprop ro.build.version.sdk) -join '').Trim()
if ($manufacturer -notmatch 'samsung') {
  Write-Error "Refusing RTL prep: ro.product.manufacturer='$manufacturer' (expected samsung). Wrong device or not RTL Galaxy."
  exit 1
}
Write-Host "RTL device: $manufacturer $model (API $sdk) serial=$Serial"

adb -s $Serial install -r -g $apk | Out-Null
adb -s $Serial shell pm clear $pkg | Out-Null
adb -s $Serial shell pm grant $pkg android.permission.CAMERA
adb -s $Serial push $video /sdcard/Download/ | Out-Null
adb -s $Serial shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Download/$VideoName" | Out-Null
adb -s $Serial shell cmd overlay enable com.android.internal.systemui.navbar.threebutton 2>$null

adb -s $Serial logcat -c
Start-Process adb -ArgumentList "-s", $Serial, "logcat" `
  -RedirectStandardOutput (Join-Path $ArtifactDir "logcat.txt") -WindowStyle Hidden
Start-Sleep -Seconds 1

# RTL devices often arrive locked / on the keyguard — drive-flow cannot see onboarding until unlocked.
adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
Start-Sleep -Seconds 1
adb -s $Serial shell wm dismiss-keyguard 2>$null | Out-Null
$sizeLine = (adb -s $Serial shell wm size) -join ''
if ($sizeLine -match '(\d+)x(\d+)') {
  $w = [int]$Matches[1]; $h = [int]$Matches[2]
  adb -s $Serial shell input swipe ([int]($w / 2)) ([int]($h * 0.78)) ([int]($w / 2)) ([int]($h * 0.34)) 300 | Out-Null
}
Start-Sleep -Seconds 2

Start-Sleep -Seconds 8
adb -s $Serial shell am start -n "$pkg/.MainActivity" | Out-Null
Start-Sleep -Seconds 4

@(
  "serial=$Serial",
  "manufacturer=$manufacturer",
  "model=$model",
  "sdk=$sdk"
) | Set-Content (Join-Path $ArtifactDir "device-info.txt")

Write-Host "SERIAL=$Serial"
exit 0
