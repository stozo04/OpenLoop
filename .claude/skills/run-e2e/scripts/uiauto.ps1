<#
.SYNOPSIS
  Drive the OpenLoop UI on a connected emulator by reading the view hierarchy
  (uiautomator) instead of screenshots — screenshots hit a per-session image
  limit and get rejected, so this is the reliable way to "see" the screen.

.USAGE
  pwsh uiauto.ps1 -Action dump                 # print every visible text/content-desc + bounds
  pwsh uiauto.ps1 -Action find -Label "Loop"   # print center coords of the element matching Label
  pwsh uiauto.ps1 -Action tap  -Label "Loop"   # tap the center of the element matching Label
  Optional: -Serial emulator-5556  (auto-detected from `adb devices` if omitted)

  Matching is case-insensitive substring against BOTH text and content-desc, with
  XML entities decoded (so -Label "B&W" matches the "B&amp;W" node).
#>
param(
  [Parameter(Mandatory = $true)][ValidateSet('dump', 'find', 'tap')][string]$Action,
  [string]$Label,
  [string]$Serial
)

function Resolve-Serial([string]$s) {
  if ($s) { return $s }
  $line = (adb devices) | Select-String 'emulator-\d+\s+device' | Select-Object -First 1
  if ($line) { return ($line.ToString() -split '\s+')[0] }
  $line = (adb devices) | Select-String '\s+device$' | Select-Object -First 1
  if ($line) { return ($line.ToString() -split '\s+')[0] }
  return $null
}

function Decode-Entities([string]$t) {
  if (-not $t) { return $t }
  $t.Replace('&amp;', '&').Replace('&lt;', '<').Replace('&gt;', '>').Replace('&quot;', '"').Replace('&#39;', "'")
}

$Serial = Resolve-Serial $Serial
if (-not $Serial) { Write-Error "No emulator/device found (adb devices)."; exit 1 }

$null = adb -s $Serial shell uiautomator dump /sdcard/ui.xml 2>$null
$xml = adb -s $Serial shell cat /sdcard/ui.xml
if (-not $xml) { Write-Error "uiautomator dump returned nothing."; exit 1 }

$nodes = [regex]::Matches($xml, '<node[^>]*>') | ForEach-Object {
  $n = $_.Value
  $bm = [regex]::Match($n, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  [pscustomobject]@{
    Text   = Decode-Entities ([regex]::Match($n, 'text="([^"]*)"').Groups[1].Value)
    Desc   = Decode-Entities ([regex]::Match($n, 'content-desc="([^"]*)"').Groups[1].Value)
    Bounds = $bm
  }
}

if ($Action -eq 'dump') {
  $nodes | Where-Object { $_.Text -or $_.Desc } | ForEach-Object {
    $label = if ($_.Text) { $_.Text } else { "[desc] $($_.Desc)" }
    $coord = if ($_.Bounds.Success) {
      $g = $_.Bounds.Groups
      "[$($g[1].Value),$($g[2].Value)][$($g[3].Value),$($g[4].Value)]"
    } else { "" }
    "{0,-45} {1}" -f $label, $coord
  } | Sort-Object -Unique
  exit 0
}

if (-not $Label) { Write-Error "-Label is required for '$Action'."; exit 1 }
$match = $nodes | Where-Object {
  $_.Bounds.Success -and (
    $_.Text -like "*$Label*" -or $_.Desc -like "*$Label*"
  )
} | Select-Object -First 1

if (-not $match) { Write-Error "No element matching '$Label'. Run -Action dump to see what's on screen."; exit 2 }

$g = $match.Bounds.Groups
$cx = [int]((([int]$g[1].Value) + ([int]$g[3].Value)) / 2)
$cy = [int]((([int]$g[2].Value) + ([int]$g[4].Value)) / 2)
$name = if ($match.Text) { $match.Text } else { $match.Desc }

if ($Action -eq 'find') {
  "match='$name' center=$cx,$cy bounds=[$($g[1].Value),$($g[2].Value)][$($g[3].Value),$($g[4].Value)]"
} else {
  $null = adb -s $Serial shell input tap $cx $cy
  "tapped '$name' at $cx,$cy"
}
