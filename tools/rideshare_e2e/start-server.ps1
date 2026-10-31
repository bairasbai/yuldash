param(
  [string]$DbPath = "$env:TEMP\claude\C--Users-Bayra-Yuldash--claude-worktrees-yuldash-tili-project-0e3eaf\c21faf18-50fb-48f8-8abb-0af213908b73\scratchpad\rsacc-20261024\stand\stand.db",
  [string]$Label = "server",
  [int]$Port = 8010
)
# Local rideshare acceptance stand: this checkout's backend, temporary SQLite, mock SMS,
# empty Telegram/FCM/Sentry keys. Records PID/start/cmd/cwd/port for cleanup.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot\..\..").Path
$evid = Join-Path $repo 'test-results\release'
$backend = Join-Path $repo 'backend'
$log = Join-Path $evid "logs\$Label.log"
$errLog = Join-Path $evid "logs\$Label.err.log"
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { throw "port $Port busy" }
$dbExisted = Test-Path $DbPath
$envs = [ordered]@{
  ENV='dev'; DATABASE_URL=('sqlite:///' + ($DbPath -replace '\\','/')); SMS_PROVIDER='mock'; SEED_DEMO='false';
  TELEGRAM_BOT_TOKEN=''; TELEGRAM_WEBHOOK_SECRET=''; ADMIN_TELEGRAM_CHAT_ID=''; ADMIN_PHONES='';
  FIREBASE_CREDENTIALS=''; SENTRY_DSN=''; VAPID_PRIVATE_KEY=''; VAPID_PUBLIC_KEY='';
  YANDEX_GEOCODER_KEY=''; YANDEX_ROUTER_KEY=''; YANDEX_WEATHER_KEY=''; YANDEX_VISION_KEY='';
  MEDIA_BASE_URL='http://127.0.0.1:8000'; CORS_ORIGINS='http://127.0.0.1:4173,http://localhost:4173';
  RATE_LIMIT_PER_MIN='5000'; PAYMENTS_PROVIDER='mock'; PYTHONIOENCODING='utf-8'; PYTHONDONTWRITEBYTECODE='1'
}
foreach ($k in $envs.Keys) { Set-Item -Path "env:$k" -Value $envs[$k] }
$psi = @{ FilePath='python'; ArgumentList=@('-m','uvicorn','app.main:app','--host','127.0.0.1','--port',"$Port",'--log-level','info');
  WorkingDirectory=$backend; RedirectStandardOutput=$log; RedirectStandardError=$errLog; WindowStyle='Hidden'; PassThru=$true }
$p = Start-Process @psi
$proc = Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)"
$rec = [ordered]@{ owner='claude/rideshare-acceptance-main-20261024'; kind='api-server'; label=$Label; pid=$p.Id;
  created=$proc.CreationDate.ToString('o'); cmd=$proc.CommandLine; cwd=$backend; ports=@($Port);
  env_public=$envs; db='scratchpad/rsacc-20261024/stand (synthetic, not evidence)'; db_existed=$dbExisted; started=(Get-Date).ToString('o') }
($rec | ConvertTo-Json -Compress -Depth 5) | Add-Content -Encoding utf8 (Join-Path $evid 'processes.jsonl')
for ($i=0; $i -lt 120; $i++) {
  try { $r = Invoke-WebRequest -UseBasicParsing http://127.0.0.1:$Port/health -TimeoutSec 2; if ($r.StatusCode -eq 200) { break } } catch { Start-Sleep -Milliseconds 500 }
}
"server pid=$($p.Id) health=$($r.StatusCode)"
