<#
.SYNOPSIS
  Create (or verify) the Pixel_8_API34 AVD — Android 14 (API 34) regression target for
  foreground-service-type bugs that only reproduce below API 35 (e.g. Crashlytics 9663c743).

.USAGE
  pwsh create-api34-avd.ps1

.NOTES
  Requires the system image already installed:
    system-images;android-34;google_apis_playstore;x86_64
  Install via Android Studio SDK Manager if missing. This script writes the AVD config files
  directly (no avdmanager/cmdline-tools required).
#>
$ErrorActionPreference = 'Stop'
$avdId = 'Pixel_8_API34'
$avdHome = Join-Path $env:USERPROFILE '.android\avd'
$avdDir = Join-Path $avdHome "$avdId.avd"
$iniPath = Join-Path $avdHome "$avdId.ini"
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$sysImg = Join-Path $sdk 'system-images\android-34\google_apis_playstore\x86_64'

if (-not (Test-Path $sysImg)) {
  Write-Error "API 34 system image missing: $sysImg — install via Android Studio SDK Manager."
  exit 1
}

New-Item -ItemType Directory -Force $avdDir | Out-Null

@(
  "avd.ini.encoding=UTF-8",
  "path=$avdDir",
  "path.rel=avd\$avdId.avd",
  'target=android-34'
) | Set-Content -Encoding UTF8 $iniPath

$config = @"
AvdId=$avdId
PlayStore.enabled=true
abi.type=x86_64
avd.ini.displayname=Pixel 8 (API 34)
avd.ini.encoding=UTF-8
disk.dataPartition.size=6G
fastboot.forceColdBoot=no
fastboot.forceFastBoot=yes
hw.accelerometer=yes
hw.arc=false
hw.audioInput=yes
hw.battery=yes
hw.camera.back=virtualscene
hw.camera.front=emulated
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.dPad=no
hw.device.manufacturer=Google
hw.device.name=pixel_8
hw.gps=yes
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.gyroscope=yes
hw.initialOrientation=portrait
hw.keyboard=yes
hw.lcd.density=420
hw.lcd.height=2400
hw.lcd.width=1080
hw.mainKeys=no
hw.ramSize=2048
hw.sdCard=yes
hw.sensors.light=yes
hw.sensors.magnetic_field=yes
hw.sensors.orientation=yes
hw.sensors.pressure=yes
hw.sensors.proximity=yes
hw.trackBall=no
image.sysdir.1=system-images\android-34\google_apis_playstore\x86_64\
runtime.network.latency=none
runtime.network.speed=full
sdcard.size=512M
showDeviceFrame=yes
skin.dynamic=yes
skin.name=pixel_8
skin.path=$sdk\skins\pixel_8
tag.display=Google Play
tag.id=google_apis_playstore
target=android-34
vm.heapSize=228
"@
$config | Set-Content -Encoding UTF8 (Join-Path $avdDir 'config.ini')

Write-Host "AVD '$avdId' ready. Verify: emulator -list-avds"
