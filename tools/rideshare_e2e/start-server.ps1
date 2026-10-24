param([string]$DbPath, [string]$Label="server", [int]$Port=8010)
. (Join-Path $PSScriptRoot 'stand-common.ps1')
$p=Get-StandPaths
if (-not $DbPath) { $DbPath=Join-Path $p.Scratch ($Label+'.db') }
$dbFull=[IO.Path]::GetFullPath($DbPath)
if (-not $dbFull.StartsWith($p.Scratch+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Database must be within the owned scratch directory' }
$env:ENV='dev'; $env:DATABASE_URL='sqlite:///'+($dbFull -replace '\\','/'); $env:SMS_PROVIDER='mock'; $env:SEED_DEMO='false'
foreach($key in @('TELEGRAM_BOT_TOKEN','TELEGRAM_WEBHOOK_SECRET','ADMIN_TELEGRAM_CHAT_ID','ADMIN_PHONES','FIREBASE_CREDENTIALS','SENTRY_DSN','VAPID_PRIVATE_KEY','VAPID_PUBLIC_KEY','YANDEX_GEOCODER_KEY','YANDEX_ROUTER_KEY','YANDEX_WEATHER_KEY','YANDEX_VISION_KEY')) { Set-Item -Path ('env:'+$key) -Value '' }
$env:MEDIA_BASE_URL='http://127.0.0.1:8000'; $env:CORS_ORIGINS='http://127.0.0.1:4173,http://localhost:4173'; $env:RATE_LIMIT_PER_MIN='5000'; $env:PAYMENTS_PROVIDER='mock'; $env:PYTHONIOENCODING='utf-8'; $env:PYTHONDONTWRITEBYTECODE='1'
$pythonExe=if($env:RSACC_PYTHON){$env:RSACC_PYTHON}else{'python'}
$process=Start-StandProcess $pythonExe @('-m','uvicorn','app.main:app','--host','127.0.0.1','--port',"$Port",'--log-level','warning','--no-access-log') (Join-Path $p.Repo 'backend') $Label @($Port)
[IO.File]::WriteAllText((Join-Path $p.Evidence 'current-db.txt'),$dbFull)
"server pid=$($process.Id)"
