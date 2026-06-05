<#
.SYNOPSIS
  Phase 1 of the pixel-sweep: cold-boot ONE AVD, install the already-built debug APK with a
  fresh app state, push + media-scan the canonical test video, switch to 3-button nav, start a
  persistent logcat capture, and launch the app.

.USAGE
  pwsh sweep-prep.ps1 -Avd Pixel_6 -ArtifactDir $env:TEMP\openloop_sweep\pixel6
  # prints "SERIAL=emulator-NNNN" on success — capture it for the next phases.

.NOTES
  - Refuses to run if another emulator is already attached (one emulator at a time — parallel
    emulators fight over host CPU/codecs and fake contention bugs).
  - Always cold boot (-no-snapshot-load): a warm-booted emulator with a wedged codec stack
    produces FALSE codec-hang findings.
  - `pm clear` gives a fresh state every run (onboarding shows again; the reverse cache cannot
    serve a stale artifact from a previous build).
#>
param(
  [Parameter(Mandatory = $true)][string]$Avd,
  [Parameter(Mandatory = $true)][string]$ArtifactDir,
  [string]$RepoRoot = "C:\Users\gates\Personal\OpenRang", # Rebranding: rename to OpenLoop manually
  [string]$VideoName = "google-pro-fold-video.mp4"
)
$ErrorActionPreference = 'Stop'
$pkg = "io.github.stozo04.openloop"
$video = Join-Path $RepoRoot $VideoName
$apk = Join-Path $RepoRoot "app\build\outputs\apk\debug\app-debug.apk"

if (-not (Test-Path $video)) { Write-Error "Canonical test video missing: $video — STOP, do not substitute."; exit 1 }
if (-not (Test-Path $apk)) { Write-Error "APK not built: $apk — run :app:assembleDebug first."; exit 1 }
New-Item -ItemType Directory -Force $ArtifactDir | Out-Null

$existing = (adb devices) | Select-String 'emulator-\d+\s+device'
if ($existing) { Write-Error "An emulator is already running ($existing). Kill it first (adb -s <serial> emu kill) — one at a time."; exit 1 }

Start-Process -FilePath "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" `
  -ArgumentList "-avd", $Avd, "-no-snapshot-load" -WindowStyle Minimized
Write-Host "Booting $Avd (cold)..."

# Wait for the serial to appear, then for full boot (budget 5 min).
$serial = $null
$deadline = (Get-Date).AddMinutes(5)
while ((Get-Date) -lt $deadline) {
  $line = (adb devices) | Select-String 'emulator-\d+\s+device' | Select-Object -First 1
  if ($line) { $serial = ($line.ToString() -split '\s+')[0]; break }
  Start-Sleep -Seconds 3
}
if (-not $serial) { Write-Error "No emulator appeared within 5 min."; exit 1 }
while ((Get-Date) -lt $deadline) {
  $boot = (adb -s $serial shell getprop sys.boot_completed 2>$null) -join ''
  if ($boot.Trim() -eq '1') { break }
  Start-Sleep -Seconds 5
}
if (((adb -s $serial shell getprop sys.boot_completed 2>$null) -join '').Trim() -ne '1') {
  Write-Error "$Avd did not finish booting within 5 min."; exit 1
}
$actualAvd = (adb -s $serial emu avd name) | Select-Object -First 1
if ($actualAvd.Trim() -ne $Avd) { Write-Error "Booted AVD is '$actualAvd', expected '$Avd'."; exit 1 }

adb -s $serial install -r -g $apk | Out-Null
# Fresh state: onboarding shows again + the reverse cache cannot serve a stale artifact.
# pm clear FIRST and EARLY: its data-wipe work is partly ASYNCHRONOUS — on a busy cold-booting
# system the deferred kill can land seconds later and SIGKILL the app if it was already
# launched (seen on the Fold: 'ShortcutService: clearing data' + 'exited due to signal 9'
# ~12 s after the command). The setup below + the settle sleep gives it time to finish;
# drive-flow's step 0 self-heals by relaunching if the race still fires.
adb -s $serial shell pm clear $pkg | Out-Null
adb -s $serial shell pm grant $pkg android.permission.CAMERA
adb -s $serial push $video /sdcard/Download/ | Out-Null
adb -s $serial shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Download/$VideoName" | Out-Null
# 3-button nav so the trim-handle edge drag can't fire the back gesture.
adb -s $serial shell cmd overlay enable com.android.internal.systemui.navbar.threebutton

adb -s $serial logcat -c
# Persistent capture: survives this script exiting. Stops when the emulator dies.
Start-Process adb -ArgumentList "-s", $serial, "logcat" `
  -RedirectStandardOutput (Join-Path $ArtifactDir "logcat.txt") -WindowStyle Hidden
Start-Sleep -Seconds 1

Start-Sleep -Seconds 8   # let pm clear's deferred work settle before the launch (see above)
adb -s $serial shell am start -n "$pkg/.MainActivity" | Out-Null
Start-Sleep -Seconds 4
Write-Host "SERIAL=$serial"
exit 0
