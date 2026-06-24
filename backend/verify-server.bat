@echo off
chcp 65001 >nul
setlocal
set "KEY=%USERPROFILE%\.ssh\id_ed25519"
set "SRV=root@85.239.52.55"
set "OPT=-i %KEY% -o StrictHostKeyChecking=accept-new -o ConnectTimeout=25 -o BatchMode=yes"
echo === VERIFY ON SERVER (localhost:8000) ===
echo.
echo --- /rides (должны быть pets_allowed/women_only/child_seat) ---
ssh %OPT% %SRV% "curl -s http://127.0.0.1:8000/rides | head -c 1400"
echo.
echo.
echo --- /driver/status код (401 = эндпоинт задеплоен; 404 = нет) ---
ssh %OPT% %SRV% "curl -s -o /dev/null -w 'driver_status=%%{http_code}\n' http://127.0.0.1:8000/driver/status"
ssh %OPT% %SRV% "curl -s -o /dev/null -w 'upload_photo=%%{http_code}\n' -X POST http://127.0.0.1:8000/upload/photo"
echo.
echo ==== VERIFY DONE ====
pause
