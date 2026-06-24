@echo off
chcp 65001 >nul
echo === VERIFY PROD (yulbash.ru) ===
echo.
echo --- /health ---
curl -sS -m 20 https://yulbash.ru/health
echo.
echo --- /rides (ищем pets_allowed/women_only) ---
curl -sS -m 20 https://yulbash.ru/rides
echo.
echo --- HTTP-коды (200=ок; 401/403=эндпоинт есть, нужен вход; 404=не задеплоен) ---
curl -s -o nul -m 20 -w "health        = %%{http_code}\n" https://yulbash.ru/health
curl -s -o nul -m 20 -w "rides         = %%{http_code}\n" https://yulbash.ru/rides
curl -s -o nul -m 20 -w "driver/status = %%{http_code}\n" https://yulbash.ru/driver/status
curl -s -o nul -m 20 -w "upload/photo  = %%{http_code}\n" -X POST https://yulbash.ru/upload/photo
echo.
echo ==== VERIFY DONE ====
pause
