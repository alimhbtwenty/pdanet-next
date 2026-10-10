@echo off
title PdaNet-Next Installer (Windows 11)
cd /d "%~dp0"

echo ===================================================
echo     PdaNet-Next Windows 11 Installer
echo     Broadband Virtual Adapter & System Tray Setup
echo ===================================================
echo.

:: Request Admin
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting Administrator privileges...
    powershell -Command "Start-Process cmd -ArgumentList '/c \"%~dpnx0\"' -Verb RunAs"
    exit /b
)

echo [+] Installing ADB Platform Tools if needed...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-adb.ps1"

echo [+] Creating Program Files destination...
set "TARGET_DIR=%ProgramFiles%\PdaNet-Next"
mkdir "%TARGET_DIR%" >nul 2>&1
mkdir "%TARGET_DIR%\bin" >nul 2>&1

copy /y "%~dp0bin\wintun.dll" "%TARGET_DIR%\bin\wintun.dll" >nul
copy /y "%~dp0PdaNetTray.ps1" "%TARGET_DIR%\PdaNetTray.ps1" >nul
copy /y "%~dp0connect.ps1" "%TARGET_DIR%\connect.ps1" >nul
copy /y "%~dp0disconnect.ps1" "%TARGET_DIR%\disconnect.ps1" >nul

echo [+] Creating Desktop & Startup shortcuts...
powershell -Command "$ws = New-Object -ComObject WScript.Shell; $s = $ws.CreateShortcut([Environment]::GetFolderPath('CommonDesktopDirectory') + '\PdaNet-Next.lnk'); $s.TargetPath = 'powershell.exe'; $s.Arguments = '-WindowStyle Hidden -ExecutionPolicy Bypass -File \"' + $env:ProgramFiles + '\PdaNet-Next\PdaNetTray.ps1\"'; $s.IconLocation = 'shell32.dll,18'; $s.Save()"

powershell -Command "$ws = New-Object -ComObject WScript.Shell; $s = $ws.CreateShortcut([Environment]::GetFolderPath('CommonStartup') + '\PdaNet-Next.lnk'); $s.TargetPath = 'powershell.exe'; $s.Arguments = '-WindowStyle Hidden -ExecutionPolicy Bypass -File \"' + $env:ProgramFiles + '\PdaNet-Next\PdaNetTray.ps1\"'; $s.IconLocation = 'shell32.dll,18'; $s.Save()"

echo.
echo ===================================================
echo   Installation Successful!
echo   PdaNet-Next icon is now active in your System Tray
echo   (next to the Windows clock).
echo ===================================================
echo.

:: Launch Tray App now
start "" powershell -WindowStyle Hidden -ExecutionPolicy Bypass -File "%TARGET_DIR%\PdaNetTray.ps1"

pause
