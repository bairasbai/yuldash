@echo off
REM Установка свежего debug-APK на эмулятор/устройство и запуск. Двойной клик в Проводнике.
setlocal
set "ADB=C:\Users\Bayra\AppData\Local\Android\Sdk\platform-tools\adb.exe"
echo === DEVICES ===
"%ADB%" devices
echo.
echo === INSTALL ===
"%ADB%" install -r "%~dp0app\build\outputs\apk\debug\app-debug.apk"
echo.
echo === LAUNCH ===
"%ADB%" shell monkey -p com.yuldash.app -c android.intent.category.LAUNCHER 1
echo.
echo ==== DONE (exit %ERRORLEVEL%) ====
pause
