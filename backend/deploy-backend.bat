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
echo --- 1) Копирую код + alembic-миграции ---
REM После разрезки монолита бэкенд = много файлов (services.py, schemas.py, routers\).
REM Копируем всю папку app\ рекурсивно (а не три файла), чтобы ничего не забыть.
REM ВАЖНО: копируем и alembic\ + alembic.ini — единственный источник истины по схеме БД
REM (раньше копировался только app\, и alembic-ревизии на прод не попадали → колонки не доезжали).
scp -r %OPT% "%BK%\app"                  %SRV%:/opt/yuldash/                       || goto :err
scp -r %OPT% "%BK%\alembic"              %SRV%:/opt/yuldash/                       || goto :err
scp %OPT% "%BK%\alembic.ini"            %SRV%:/opt/yuldash/alembic.ini            || goto :err

echo --- 2) chown (чтобы alembic и venv были доступны пользователю приложения) ---
ssh %OPT% %SRV% "chown -R yuldash:yuldash /opt/yuldash" || goto :err

echo --- 3) Миграции БД: alembic upgrade head (идемпотентно, единый путь) ---
REM Все ревизии идемпотентны (inspect-before-alter / create_all): повторный запуск = no-op.
REM ПЕРВЫЙ РАЗ (онбординг прода на alembic) выполняется так же — 0001 baseline это create_all,
REM существующие таблицы не трогает, 0002-0004 добавляют/убирают только недостающее.
REM Инструкция + бэкап/откат: docs/deploy-migrations.md.
ssh %OPT% %SRV% "cd /opt/yuldash && sudo -u yuldash ./.venv/bin/alembic upgrade head" || goto :err
REM PostGIS (best-effort: если не установлен — работает Python-фолбэк радиуса). Разово, не валит деплой.
ssh %OPT% %SRV% "sudo -u postgres psql -d yuldash -c \"CREATE EXTENSION IF NOT EXISTS postgis;\" & sudo -u postgres psql -d yuldash -c \"CREATE INDEX IF NOT EXISTS idx_ride_from_geog ON ride USING GIST ((ST_MakePoint(from_lng, from_lat)::geography)) WHERE from_lat IS NOT NULL;\""

echo --- 4) Рестарт сервиса ---
ssh %OPT% %SRV% "systemctl restart yuldash-api && sleep 2 && systemctl is-active yuldash-api" || goto :err

echo --- 5) Проверка здоровья (на сервере) ---
ssh %OPT% %SRV% "curl -s http://127.0.0.1:8000/health"
echo.
echo --- 6) Проверка публично + новые поля в /rides ---
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
