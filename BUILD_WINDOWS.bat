@echo off
setlocal
cd /d "%~dp0"
where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle was not found in PATH.
  echo Install Gradle 8.9 or open this folder in Android Studio and select Gradle 8.9.
  exit /b 1
)
gradle --version
echo.
echo Building debug APK...
gradle --no-daemon testDebugUnitTest assembleDebug
if errorlevel 1 exit /b %errorlevel%
echo.
echo APK created at:
echo %CD%\app\build\outputs\apk\debug\app-debug.apk
endlocal
