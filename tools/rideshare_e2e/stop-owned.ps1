param([Parameter(Mandatory=$true)][int]$ProcessId)
. (Join-Path $PSScriptRoot 'stand-common.ps1')
$paths = Get-StandPaths
$records = @(Get-Content -LiteralPath (Join-Path $paths.Evidence 'processes.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
$record = $records | Where-Object { $_.owner -eq $paths.Owner -and $_.pid -eq $ProcessId -and $_.cmd } | Select-Object -Last 1
if (-not $record) { throw 'No task-owned process record; nothing stopped' }
function Test-Identity($entry) {
  $actual = Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)"
  if (-not $actual) { return $false }
  if ($actual.CreationDate.ToString('o') -ne $entry.created -or $actual.CommandLine -ne $entry.cmd) { throw "PID $($entry.pid) has a different identity; nothing stopped" }
  return $true
}
$entries = @($record.children) + @($record)
foreach ($entry in $entries) { if ($entry) { [void](Test-Identity $entry) } }
# First ask the recorded parent's window to close; hidden CLI processes usually have no window.
if (Test-Identity $record) { $process = Get-Process -Id $ProcessId; if ($process.CloseMainWindow()) { $process.WaitForExit(3000) | Out-Null } }
foreach ($entry in $entries) { if ($entry -and (Test-Identity $entry)) { Stop-Process -Id $entry.pid; Start-Sleep -Milliseconds 250; if (Test-Identity $entry) { throw "Owned PID $($entry.pid) did not stop" } } }
foreach ($port in $record.ports) { if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Port $port remains occupied; do not stop an unverified owner" } }
(@{owner=$paths.Owner;stopped_pid=$ProcessId;at=(Get-Date).ToString('o');ports_free=$true} | ConvertTo-Json -Compress) | Add-Content -LiteralPath (Join-Path $paths.Evidence 'processes.jsonl') -Encoding UTF8
