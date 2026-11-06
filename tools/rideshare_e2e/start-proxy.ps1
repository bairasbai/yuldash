param([string]$Label = "proxy")
# Wire proxy :8000 -> API :8010 with control :8099; records the process for cleanup.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot\..\..").Path
$evid = Join-Path $repo 'test-results\release'
foreach ($port in 8000, 8099) { if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "port $port busy" } }
$env:WIRE_LOG = Join-Path $evid "logs\$Label-wire.jsonl"
# cmd.exe parent keeps node from being an "orphan" for the hourly ClaudeReaper (it killed the first proxy 10:25).
$p = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/d','/c','node',(Join-Path $PSScriptRoot 'proxy.mjs')) -WorkingDirectory $PSScriptRoot `
  -RedirectStandardOutput (Join-Path $evid "logs\$Label.log") -RedirectStandardError (Join-Path $evid "logs\$Label.err.log") -WindowStyle Hidden -PassThru
Start-Sleep 1
$proc = Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)"
$child = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($p.Id) AND Name='node.exe'"
$rec = [ordered]@{ owner='claude/rideshare-acceptance-main-20261024'; kind='wire-proxy'; label=$Label; pid=$p.Id; child_pid=$child.ProcessId; child_created=$child.CreationDate.ToString('o');
  created=$proc.CreationDate.ToString('o'); cmd=$proc.CommandLine; cwd=$PSScriptRoot; ports=@(8000,8099); wire_log=$env:WIRE_LOG; started=(Get-Date).ToString('o') }
($rec | ConvertTo-Json -Compress -Depth 5) | Add-Content -Encoding utf8 (Join-Path $evid 'processes.jsonl')
for ($i=0; $i -lt 40; $i++) { try { $r = Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8000/health -TimeoutSec 2; if ($r.StatusCode -eq 200) { break } } catch { Start-Sleep -Milliseconds 300 } }
"proxy pid=$($p.Id) health-through-proxy=$($r.StatusCode)"
