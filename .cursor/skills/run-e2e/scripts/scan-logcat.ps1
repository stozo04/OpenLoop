<#
.SYNOPSIS
  Scan a captured logcat file for the concerning signatures that matter for an
  OpenLoop E2E run, print a count table, and surface the reverse-pipeline
  terminal events. Use this to build the report's "concerning logs" section.

.USAGE
  pwsh scan-logcat.ps1 -LogFile C:\path\to\logcat_run.txt

  Exit code is 0 always; read the table. Any nonzero count in a CRASH-class row
  is a hard finding; the CHURN-class rows are advisory (resource-pressure smell).
#>
param([Parameter(Mandatory = $true)][string]$LogFile)

if (-not (Test-Path $LogFile)) { Write-Error "Log file not found: $LogFile"; exit 1 }
$lines = Get-Content $LogFile

# class = CRASH (hard finding) | TIMEOUT (degraded UX) | CHURN (advisory smell)
$sigs = @(
  @{ Name = 'FATAL EXCEPTION (app crash)';            Class = 'CRASH';   Pattern = 'FATAL EXCEPTION' }
  @{ Name = 'ANR (app not responding)';               Class = 'CRASH';   Pattern = 'ANR in ' }
  @{ Name = 'process died';                           Class = 'CRASH';   Pattern = 'Process .*io\.github\.stozo04\.openloop.* died' }
  @{ Name = 'surface has been released (b09e527)';    Class = 'CRASH';   Pattern = 'surface has been released' }
  @{ Name = 'dequeueOutputBuffer native (3a506c4e)';  Class = 'CRASH';   Pattern = 'native_dequeueOutputBuffer' }
  @{ Name = 'MediaCodec CodecException';              Class = 'CRASH';   Pattern = 'CodecException' }
  @{ Name = 'FGS type unknown (API 34 regression)';  Class = 'CRASH';   Pattern = 'InvalidForegroundServiceTypeException|Starting FGS with type unknown' }
  @{ Name = 'reverse preview timeout (120s bucket)';  Class = 'TIMEOUT'; Pattern = 'ensureReversed\.timeout|Timed out after' }
  @{ Name = 'reverse preview failure (non-fatal)';    Class = 'TIMEOUT'; Pattern = 'reverse_preview_failure' }
  @{ Name = 'codec reclaim pressure';                 Class = 'CHURN';   Pattern = 'keep callback message for reclaim' }
  @{ Name = 'dead-thread handler race';               Class = 'CHURN';   Pattern = 'sending message to a Handler on a dead thread' }
  @{ Name = 'codec buffer starvation';                Class = 'CHURN';   Pattern = 'received null buffer|discarded an unknown buffer|frameIndex not found' }
  @{ Name = 'codec components created (c2.*)';        Class = 'CHURN';   Pattern = 'Created component \[c2\.' }
)

"=== Concerning log signatures: $([System.IO.Path]::GetFileName($LogFile)) ==="
"{0,-7} {1,-42} {2}" -f 'CLASS', 'SIGNATURE', 'COUNT'
foreach ($s in $sigs) {
  $c = ($lines | Select-String -Pattern $s.Pattern).Count
  $flag = if ($c -gt 0 -and $s.Class -eq 'CRASH') { '  <-- HARD FINDING' } else { '' }
  "{0,-7} {1,-42} {2}{3}" -f $s.Class, $s.Name, $c, $flag
}

"`n=== Reverse pipeline terminal events (OpenLoopReverse) ==="
$lines | Select-String -Pattern 'reverse\.complete|reverse\.pass1\.done|reverse\.pass2\.done|ensureReversed\.(ok|timeout|fail|cancelled|stale|start)|reverse\.contention_retry|surface_pipeline\.retry' |
  Select-Object -Last 20 |
  ForEach-Object { ($_.Line -replace '^.*OpenLoopReverse: ', '') -replace '^.*OpenLoopViewModel.*?: ', 'VM: ' }

"`n=== Render worker outcome (boomerang save) ==="
$lines | Select-String -Pattern 'BoomerangRenderWorker|Worker result (SUCCESS|FAILURE|RETRY)|TransformerInternal: (Init|Release)' |
  Select-Object -Last 8 |
  ForEach-Object { ($_.Line -replace '^(\d\d-\d\d \d\d:\d\d:\d\d).*?([A-Za-z]+: .*)$', '$1  $2') }
exit 0
