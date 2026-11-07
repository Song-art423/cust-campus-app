@echo off
chcp 65001 >nul
cd /d "%~dp0"

rem ---- Toolchain lives at <workspace>/.toolchain ----
set "JAVA_HOME=%~dp0..\..\.toolchain\jdk-17.0.2"
set "ANDROID_HOME=%~dp0..\..\.toolchain\android-sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "%JAVA_HOME%\bin\java.exe" goto nojdk
if not exist "%ANDROID_HOME%\platforms\android-34\android.jar" goto nosdk

echo [*] Building debug APK...
echo.
call gradlew.bat assembleDebug
if errorlevel 1 goto failed

echo.
echo [OK] Build finished.
echo [i] APK: app\build\outputs\apk\debug\app-debug.apk
echo [i] Install to a connected phone: adb install -r app\build\outputs\apk\debug\app-debug.apk
echo.
pause
exit /b 0

:nojdk
echo [!] JDK 17 not found at:
echo     %JAVA_HOME%
echo [i] Restore the .toolchain folder, or open this project in Android Studio.
pause
exit /b 1

:nosdk
echo [!] Android SDK platform 34 not found at:
echo     %ANDROID_HOME%
echo [i] Restore the .toolchain folder, or open this project in Android Studio.
pause
exit /b 1

:failed
echo.
echo [!] Build failed. Read the messages above.
pause
exit /b 1
