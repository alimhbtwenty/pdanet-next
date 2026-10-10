@echo off
setlocal EnableDelayedExpansion
title PdaNet-Next Installer (Windows 11)
cd /d "%~dp0"

echo ===================================================
echo     PdaNet-Next Windows 11 Installer
echo     Broadband Virtual Adapter and System Tray Setup
echo ===================================================
echo.

:: Request Admin
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting Administrator privileges...
    powershell -Command "Start-Process cmd -ArgumentList '/c \"%~dpnx0\"' -Verb RunAs"
    exit /b
)

echo [+] Preparing installation directory...
set "TARGET_DIR=%ProgramFiles%\PdaNet-Next"
if not exist "%TARGET_DIR%\bin" mkdir "%TARGET_DIR%\bin" >nul 2>&1

echo [+] Installing ADB and network components...
copy /y "%~dp0bin\*.*" "%TARGET_DIR%\bin\" >nul
copy /y "%~dp0*.ps1" "%TARGET_DIR%\" >nul

echo [+] Adding PdaNet-Next to system PATH...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p = [Environment]::GetEnvironmentVariable('Path', 'Machine'); $bin = '%ProgramFiles%\PdaNet-Next\bin'; if ($p -notlike ('*' + $bin + '*')) { [Environment]::SetEnvironmentVariable('Path', $p + ';' + $bin, 'Machine') }"

echo [+] Creating Desktop and Startup shortcuts...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ws = New-Object -ComObject WScript.Shell; $d = [Environment]::GetFolderPath('CommonDesktopDirectory') + '\PdaNet-Next.lnk'; $s = $ws.CreateShortcut($d); $s.TargetPath = 'powershell.exe'; $s.Arguments = '-WindowStyle Hidden -ExecutionPolicy Bypass -File \"' + $env:ProgramFiles + '\PdaNet-Next\PdaNetTray.ps1\"'; $s.IconLocation = 'shell32.dll,18'; $s.Save()"

powershell -NoProfile -ExecutionPolicy Bypass -Command "$ws = New-Object -ComObject WScript.Shell; $st = [Environment]::GetFolderPath('CommonStartup') + '\PdaNet-Next.lnk'; $s = $ws.CreateShortcut($st); $s.TargetPath = 'powershell.exe'; $s.Arguments = '-WindowStyle Hidden -ExecutionPolicy Bypass -File \"' + $env:ProgramFiles + '\PdaNet-Next\PdaNetTray.ps1\"'; $s.IconLocation = 'shell32.dll,18'; $s.Save()"

echo.
echo ===================================================
echo   Installation Successful!
echo   PdaNet-Next is now active in your System Tray
echo   (next to the Windows clock).
echo ===================================================
echo.

:: Launch Tray App
start "" powershell -WindowStyle Hidden -ExecutionPolicy Bypass -File "%TARGET_DIR%\PdaNetTray.ps1"

pause
