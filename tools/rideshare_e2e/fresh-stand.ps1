param([Parameter(Mandatory=$true)][string]$Name)
# Replace this checkout's API with a fresh SQLite stand and reset the Android QA app.
$ErrorActionPreference = 'Stop'
$H = $PSScriptRoot
$evid = (Resolve-Path "$H\..\..\test-results\release").Path
$S = "$env:TEMP\claude\C--Users-Bayra-Yuldash--claude-worktrees-yuldash-tili-project-0e3eaf\c21faf18-50fb-48f8-8abb-0af213908b73\scratchpad\rsacc-20261024\stand"
$listen = Get-NetTCPConnection -State Listen -LocalPort 8010 -ErrorAction SilentlyContinue
if ($listen) {
  $p = Get-CimInstance Win32_Process -Filter "ProcessId=$($listen[0].OwningProcess)"
  $runner = Join-Path $H 'qa_server.py'
  $mine = Get-Content -LiteralPath (Join-Path $evid 'processes.jsonl') |
    ForEach-Object { $_ | ConvertFrom-Json } |
    Where-Object {
      $_.kind -eq 'api-server' -and $_.pid -eq $p.ProcessId -and
      [datetime]$_.created -eq $p.CreationDate -and $_.cmd -ceq $p.CommandLine
    }
  if (-not $mine -or $p.CommandLine -notmatch [regex]::Escape($runner)) { throw "8010 is not this checkout's API server" }
  Stop-Process -Id $p.ProcessId; Start-Sleep 2
  ('{"stopped_pid":' + $p.ProcessId + ',"at":"' + (Get-Date).ToString('o') + '","how":"Stop-Process (fresh-stand)"}') | Add-Content -Encoding utf8 (Join-Path $evid 'processes.jsonl')
}
if (Test-Path "$S\$Name.db") { throw "db $Name exists" }
& "$H\start-server.ps1" -Label "server-$Name" -Port 8010 -DbPath "$S\$Name.db"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5560 shell pm clear com.yuldash.app | Out-Null
& $adb -s emulator-5560 shell pm grant com.yuldash.app android.permission.POST_NOTIFICATIONS
[IO.File]::WriteAllText("$evid\current-db.txt", "$S\$Name.db")
"STAND_DB=$S\$Name.db"
