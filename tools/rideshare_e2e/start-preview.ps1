param([string]$Label = "pwa-preview")
# Serve this checkout's built PWA (webapp/dist) on 127.0.0.1:4173 and record the process.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot\..\..").Path
$evid = Join-Path $repo 'test-results\release'
$web = Join-Path $repo 'webapp'
if (Get-NetTCPConnection -State Listen -LocalPort 4173 -ErrorAction SilentlyContinue) { throw 'port 4173 busy' }
$vite = Join-Path $web 'node_modules\vite\bin\vite.js'
$p = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/d','/c','node',$vite,'preview','--host','127.0.0.1','--port','4173','--strictPort') `
  -WorkingDirectory $web -RedirectStandardOutput (Join-Path $evid "logs\$Label.log") -RedirectStandardError (Join-Path $evid "logs\$Label.err.log") -WindowStyle Hidden -PassThru
Start-Sleep 1
$proc = Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)"
$child = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($p.Id) AND Name='node.exe'"
$rec = [ordered]@{ owner='claude/rideshare-acceptance-main-20261024'; kind='pwa-preview'; label=$Label; pid=$p.Id; child_pid=$child.ProcessId; child_created=$child.CreationDate.ToString('o');
  created=$proc.CreationDate.ToString('o'); cmd=$proc.CommandLine; cwd=$web; ports=@(4173); started=(Get-Date).ToString('o') }
($rec | ConvertTo-Json -Compress -Depth 5) | Add-Content -Encoding utf8 (Join-Path $evid 'processes.jsonl')
for ($i=0; $i -lt 60; $i++) { try { $r = Invoke-WebRequest -UseBasicParsing http://127.0.0.1:4173/ -TimeoutSec 2; if ($r.StatusCode -eq 200) { break } } catch { Start-Sleep -Milliseconds 500 } }
"preview pid=$($p.Id) status=$($r.StatusCode)"
