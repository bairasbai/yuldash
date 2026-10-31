param([Parameter(Mandatory=$true)][string]$Name)
# Replace the API process with a new one on a brand-new SQLite file; clear the app and PWA profiles.
$ErrorActionPreference = 'Stop'
$H = $PSScriptRoot
$evid = (Resolve-Path "$H\..\..\test-results\release").Path
$S = "$env:TEMP\claude\C--Users-Bayra-Yuldash--claude-worktrees-yuldash-tili-project-0e3eaf\c21faf18-50fb-48f8-8abb-0af213908b73\scratchpad\rsacc-20261024\stand"
$listen = Get-NetTCPConnection -State Listen -LocalPort 8010 -ErrorAction SilentlyContinue
if ($listen) {
  $p = Get-CimInstance Win32_Process -Filter "ProcessId=$($listen[0].OwningProcess)"
  $mine = Get-Content (Join-Path $evid 'processes.jsonl') | Where-Object { $_ -match "`"pid`":$($p.ProcessId)," }
  if (-not $mine -or $p.CommandLine -notmatch 'uvicorn app.main:app') { throw "8010 is not my API server" }
  Stop-Process -Id $p.ProcessId; Start-Sleep 2
  ('{"stopped_pid":' + $p.ProcessId + ',"at":"' + (Get-Date).ToString('o') + '","how":"Stop-Process (fresh-stand)"}') | Add-Content -Encoding utf8 (Join-Path $evid 'processes.jsonl')
}
if (Test-Path "$S\$Name.db") { throw "db $Name exists" }
& "$H\start-server.ps1" -Label "server-$Name" -Port 8010 -DbPath "$S\$Name.db"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5560 shell pm clear com.yuldash.app | Out-Null
& $adb -s emulator-5560 shell pm grant com.yuldash.app android.permission.POST_NOTIFICATIONS
Remove-Item -Recurse -Force "$env:LOCALAPPDATA\Temp\rsacc24\pw" -ErrorAction SilentlyContinue
[IO.File]::WriteAllText("$evid\current-db.txt", "$S\$Name.db")
"STAND_DB=$S\$Name.db"
