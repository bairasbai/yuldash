@echo off
REM Build release APK and copy artifact to the project root.
setlocal
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ROOT=%~dp0.."
set "OUT=%~dp0app\build\outputs\apk\release\app-release.apk"
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyy-MM-dd-HHmm"') do set "STAMP=%%i"

cd /d "%~dp0"
echo === YULDASH BUILD (assembleRelease) ===
echo JAVA_HOME=%JAVA_HOME%
echo.
call gradlew.bat :app:assembleRelease --no-daemon
if errorlevel 1 goto :err

copy /Y "%OUT%" "%ROOT%\yuldash-release-%STAMP%.apk" >nul
echo.
echo APK: %ROOT%\yuldash-release-%STAMP%.apk
echo ==== BUILD OK ====
pause
exit /b 0

:err
echo.
echo ==== BUILD FAILED (exit %ERRORLEVEL%) ====
pause
exit /b %ERRORLEVEL%
