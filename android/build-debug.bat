@echo off
REM Сборка debug-APK Юлдаш. Двойной клик в Проводнике запускает сборку и оставляет окно с результатом.
setlocal
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
cd /d "%~dp0"
echo === YULDASH BUILD (assembleDebug) ===
echo JAVA_HOME=%JAVA_HOME%
echo.
call gradlew.bat :app:assembleDebug --no-daemon
echo.
echo ==== EXIT CODE: %ERRORLEVEL% (0 = OK) ====
pause
