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
$DbPath = [IO.Path]::GetFullPath($DbPath)
if (Test-Path -LiteralPath $DbPath) { throw 'Choose a fresh SQLite file for this stand' }
$dbDir = Split-Path -Parent $DbPath
if (-not (Test-Path -LiteralPath $dbDir -PathType Container)) { throw 'Create the test database directory first' }
$envs = [ordered]@{
  ENV='dev'; DATABASE_URL=('sqlite:///' + ($DbPath -replace '\\','/')); SMS_PROVIDER='mock'; SEED_DEMO='false';
  TELEGRAM_BOT_TOKEN=''; TELEGRAM_WEBHOOK_SECRET=''; ADMIN_TELEGRAM_CHAT_ID=''; ADMIN_PHONES='';
  FIREBASE_CREDENTIALS=''; SENTRY_DSN=''; VAPID_PRIVATE_KEY=''; VAPID_PUBLIC_KEY='';
  YANDEX_GEOCODER_KEY=''; YANDEX_ROUTER_KEY=''; YANDEX_WEATHER_KEY=''; YANDEX_VISION_KEY='';
  MEDIA_BASE_URL='http://127.0.0.1:8000'; CORS_ORIGINS='http://127.0.0.1:4173,http://localhost:4173';
  RATE_LIMIT_PER_MIN='5000'; PAYMENTS_PROVIDER='mock'; REDIS_URL=''; WEATHER_WARNINGS_ENABLED='false'; DAILY_DIGEST_ENABLED='false';
  PYTHONIOENCODING='utf-8'; PYTHONDONTWRITEBYTECODE='1'; PYTHONPATH=$backend
}
foreach ($k in $envs.Keys) { Set-Item -Path "env:$k" -Value $envs[$k] }
if ($env:RSACC_TELEGRAM_SECRET) {
  $env:TELEGRAM_BOT_TOKEN = 'synthetic-local-qa-bot'
  $env:TELEGRAM_WEBHOOK_SECRET = $env:RSACC_TELEGRAM_SECRET
}
$runner = Join-Path $PSScriptRoot 'qa_server.py'
$psi = @{ FilePath='python'; ArgumentList=@("-B", ('"{0}"' -f $runner), ('"{0}"' -f $DbPath), "$Port");
  WorkingDirectory=$backend; RedirectStandardOutput=$log; RedirectStandardError=$errLog; WindowStyle='Hidden'; PassThru=$true }
$p = Start-Process @psi
$proc = Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)"
$rec = [ordered]@{ owner='claude/rideshare-acceptance-main-20261024'; kind='api-server'; label=$Label; pid=$p.Id;
  created=$proc.CreationDate.ToString('o'); cmd=$proc.CommandLine; cwd=$backend; ports=@($Port);
  env_public=$envs; db='fresh test SQLite'; db_existed=$false; started=(Get-Date).ToString('o') }
($rec | ConvertTo-Json -Compress -Depth 5) | Add-Content -Encoding utf8 (Join-Path $evid 'processes.jsonl')
for ($i=0; $i -lt 120; $i++) {
  if ($p.HasExited) { throw 'Stand exited before it became ready' }
  try { $r = Invoke-WebRequest -UseBasicParsing http://127.0.0.1:$Port/health/ready -TimeoutSec 2; if ($r.StatusCode -eq 200) { break } } catch { Start-Sleep -Milliseconds 500 }
}
if ($r.StatusCode -ne 200) { throw 'Stand did not become ready' }
"server pid=$($p.Id) health=$($r.StatusCode)"
