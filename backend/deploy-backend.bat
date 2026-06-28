@echo off
REM Деплой бэкенда Юлдаш на прод (yulbash.ru). Двойной клик в Проводнике.
REM Копирует изменённые файлы, мигрирует БД, перезапускает сервис, проверяет здоровье.
setlocal
set "KEY=%USERPROFILE%\.ssh\id_ed25519"
set "SRV=root@85.239.52.55"
set "BK=C:\Users\Bayra\Yuldash\backend"
set "OPT=-i %KEY% -o StrictHostKeyChecking=accept-new -o ConnectTimeout=25 -o BatchMode=yes"

echo === YULDASH BACKEND DEPLOY ===
echo.
echo --- 1) Копирую код + миграции ---
REM После разрезки монолита бэкенд = много файлов (services.py, schemas.py, routers\).
REM Копируем всю папку app\ рекурсивно (а не три файла), чтобы ничего не забыть.
scp -r %OPT% "%BK%\app"                  %SRV%:/opt/yuldash/                       || goto :err
scp %OPT% "%BK%\migrate_premium.sql"    %SRV%:/tmp/migrate_premium.sql            || goto :err
scp %OPT% "%BK%\migrate_oauth.sql"      %SRV%:/tmp/migrate_oauth.sql              || goto :err
scp %OPT% "%BK%\migrate_whatsapp.sql"   %SRV%:/tmp/migrate_whatsapp.sql           || goto :err
scp %OPT% "%BK%\migrate_logout.sql"     %SRV%:/tmp/migrate_logout.sql             || goto :err

echo --- 2) Миграции БД (ALTER TABLE, идемпотентно) ---
ssh %OPT% %SRV% "sudo -u postgres psql -d yuldash -v ON_ERROR_STOP=1 -f /tmp/migrate_premium.sql"  || goto :err
ssh %OPT% %SRV% "sudo -u postgres psql -d yuldash -v ON_ERROR_STOP=1 -f /tmp/migrate_oauth.sql"    || goto :err
ssh %OPT% %SRV% "sudo -u postgres psql -d yuldash -v ON_ERROR_STOP=1 -f /tmp/migrate_whatsapp.sql" || goto :err
ssh %OPT% %SRV% "sudo -u postgres psql -d yuldash -v ON_ERROR_STOP=1 -f /tmp/migrate_logout.sql"   || goto :err

echo --- 3) chown + рестарт сервиса ---
ssh %OPT% %SRV% "chown -R yuldash:yuldash /opt/yuldash && systemctl restart yuldash-api && sleep 2 && systemctl is-active yuldash-api" || goto :err

echo --- 4) Проверка здоровья (на сервере) ---
ssh %OPT% %SRV% "curl -s http://127.0.0.1:8000/health"
echo.
echo --- 5) Проверка публично + новые поля в /rides ---
curl -s https://yulbash.ru/health
echo.
curl -s https://yulbash.ru/rides
echo.
echo.
echo ==== DEPLOY OK (exit 0) ====
pause
exit /b 0

:err
echo.
echo ==== DEPLOY FAILED (errorlevel %errorlevel%) — смотри сообщение выше ====
echo Если висело подключение — это ТСПУ режет SSH, повтори запуск (или включи VPN).
pause
exit /b 1
