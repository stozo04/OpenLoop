[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$version = "8.30.1"
$root = Split-Path -Parent $PSScriptRoot
$toolDir = Join-Path $root "build/tools/gitleaks/$version"

$os = if ([Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::Windows)) {
    "windows"
} elseif ([Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::OSX)) {
    "darwin"
} elseif ([Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::Linux)) {
    "linux"
} else {
    throw "Gitleaks bootstrap does not support this operating system"
}
$arch = switch ([Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()) {
    "X64" { "x64" }
    "Arm64" { "arm64" }
    "X86" {
        if ($os -eq "darwin") { throw "Gitleaks does not publish a macOS x86 binary" }
        "x32"
    }
    "Arm" {
        if ($os -ne "linux") { throw "Gitleaks only publishes its ARMv7 binary for Linux" }
        "armv7"
    }
    default { throw "Gitleaks bootstrap does not support architecture $_" }
}
$extension = if ($os -eq "windows") { "zip" } else { "tar.gz" }
$asset = "gitleaks_${version}_${os}_${arch}.${extension}"
$executable = Join-Path $toolDir $(if ($os -eq "windows") { "gitleaks.exe" } else { "gitleaks" })

if (-not (Test-Path $executable)) {
    New-Item -ItemType Directory -Force $toolDir | Out-Null
    $base = "https://github.com/gitleaks/gitleaks/releases/download/v$version"
    $archive = Join-Path $toolDir $asset
    $checksums = Join-Path $toolDir "checksums.txt"

    Add-Type -AssemblyName System.Net.Http
    $client = [Net.Http.HttpClient]::new()
    $client.DefaultRequestHeaders.UserAgent.ParseAdd("OpenLoop-pre-pr-sweep")
    try {
        [IO.File]::WriteAllBytes($archive, $client.GetByteArrayAsync("$base/$asset").GetAwaiter().GetResult())
        [IO.File]::WriteAllBytes($checksums, $client.GetByteArrayAsync("$base/gitleaks_${version}_checksums.txt").GetAwaiter().GetResult())
    } finally {
        $client.Dispose()
    }

    $checksumLine = Get-Content $checksums | Where-Object { $_ -match "\s+$([regex]::Escape($asset))$" } | Select-Object -First 1
    if (-not $checksumLine) { throw "No checksum published for $asset" }
    $expected = ($checksumLine -split '\s+')[0].ToUpperInvariant()
    $actual = (Get-FileHash $archive -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actual -ne $expected) { throw "Checksum mismatch for $asset" }

    if ($extension -eq "zip") {
        Expand-Archive $archive -DestinationPath $toolDir -Force
    } else {
        & tar -xzf $archive -C $toolDir
        if ($LASTEXITCODE -ne 0) { throw "tar failed to extract $asset" }
        & chmod +x $executable
    }
}

if (-not (Test-Path $executable)) { throw "Gitleaks executable missing after bootstrap: $executable" }
(Resolve-Path $executable).Path
