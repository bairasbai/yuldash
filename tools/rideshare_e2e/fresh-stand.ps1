param([Parameter(Mandatory=$true)][string]$Name,[int]$OwnedServerPid=0)
. (Join-Path $PSScriptRoot 'stand-common.ps1')
$p=Get-StandPaths
if ($Name -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid stand name' }
if ($OwnedServerPid) { & (Join-Path $PSScriptRoot 'stop-owned.ps1') -ProcessId $OwnedServerPid }
$db=Join-Path $p.Scratch ($Name+'.db')
if(Test-Path -LiteralPath $db) { throw 'Database exists; choose a new stand name. Existing data is preserved.' }
& (Join-Path $PSScriptRoot 'start-server.ps1') -DbPath $db -Label ('server-'+$Name)
# Android app data and existing browser profiles are deliberately preserved.
# Use a new RSACC_PROFILE_ROOT for isolated profiles; root owns emulator reset decisions.
