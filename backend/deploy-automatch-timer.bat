@echo off
REM Разовая установка авто-подбора водителя (systemd-таймер раз в минуту) на прод.
REM Двойной клик. Ставит и включает таймер, затем делает БЕЗОПАСНЫЙ сухой прогон
REM (--dry-run: показывает, кого бы подобрал, НИЧЕГО не меняя). Код уже должен быть
REM залит основным deploy-backend.bat (в нём файл app\automatch.py).
setlocal
set "KEY=%USERPROFILE%\.ssh\id_ed25519"
set "SRV=root@85.239.52.55"
set "BK=C:\Users\Bayra\Yuldash\backend"
set "OPT=-i %KEY% -o StrictHostKeyChecking=accept-new -o ConnectTimeout=25 -o BatchMode=yes"

echo === YULDASH AUTO-MATCH TIMER SETUP ===
echo.
echo --- 1) Копирую systemd-юниты ---
scp %OPT% "%BK%\deploy\yuldash-automatch.service" %SRV%:/etc/systemd/system/yuldash-automatch.service || goto :err
scp %OPT% "%BK%\deploy\yuldash-automatch.timer"   %SRV%:/etc/systemd/system/yuldash-automatch.timer   || goto :err

echo --- 2) Включаю таймер (раз в минуту) ---
ssh %OPT% %SRV% "systemctl daemon-reload && systemctl enable --now yuldash-automatch.timer && systemctl is-active yuldash-automatch.timer" || goto :err

echo --- 3) Безопасная проверка (--dry-run: ничего не меняет) ---
ssh %OPT% %SRV% "cd /opt/yuldash && sudo -u yuldash .venv/bin/python -m app.automatch --dry-run"
echo.
echo ==== AUTO-MATCH ON (exit 0) ====
echo Выключить при желании: ssh %SRV% "systemctl disable --now yuldash-automatch.timer"
pause
exit /b 0

:err
echo.
echo ==== SETUP FAILED (errorlevel %errorlevel%) — смотри сообщение выше ====
echo Если висело подключение — ТСПУ режет SSH, повтори запуск (или включи VPN).
pause
exit /b 1
