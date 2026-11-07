@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo ============================================
echo  Campus System Recon
echo ============================================
echo.

if not exist config.json (
    echo [!] config.json not found, creating from template...
    copy config.example.json config.json >nul
    echo.
    echo [OK] Created config.json
    echo [i] Open config.json, paste your Cookie, save, then run this again.
    echo.
    pause
    exit /b 1
)

findstr /C:"PASTE_YOUR_COOKIE_HERE" config.json >nul 2>&1
if not errorlevel 1 (
    echo [!] config.json still has the placeholder value.
    echo [i] Fill in your real Cookie first, then run this again.
    echo.
    pause
    exit /b 1
)

echo [*] Checking dependencies...
python -m pip install -q -r requirements.txt
if errorlevel 1 (
    echo [!] pip install failed. Check Python is installed and in PATH.
    pause
    exit /b 1
)

echo [*] Running recon...
echo.
python recon.py
echo.
echo ============================================
echo  Done. Check report.md and the dumps folder.
echo ============================================
pause
