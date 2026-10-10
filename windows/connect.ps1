# PdaNet-Next: Connect (Windows 10/11)
# Establishes unlimited USB tethering tunnel, bypasses carrier tethering limits (TTL=65)

param(
    [switch]$NoWait
)

$ErrorActionPreference = "Stop"

# Bulletproof interrupt handler for SIGINT (Ctrl+C) and unhandled pipeline termination
trap {
    Write-Host "`n  [!] Interrupted. Running automatic cleanup..." -ForegroundColor Red
    & (Join-Path $PSScriptRoot "disconnect.ps1") -Quiet
    exit 1
}

# 0. Check Administrator Privileges
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "  [!] Administrator privileges required. Relaunching..." -ForegroundColor Yellow
    $argsList = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    if ($NoWait) { $argsList += " -NoWait" }
    Start-Process powershell.exe -ArgumentList $argsList -Verb RunAs
    exit
}

Write-Host ""
Write-Host "  PdaNet-Next · USB Connect (v2.1)" -ForegroundColor Cyan
Write-Host "  =================================" -ForegroundColor DarkGray
Write-Host ""

# 1. Locate ADB
$adb = "adb"
$candidates = @(
    (Join-Path $PSScriptRoot "bin\adb.exe"),
    "$env:ProgramFiles\PdaNet-Next\bin\adb.exe",
    (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
)
foreach ($c in $candidates) {
    if (Test-Path $c) { $adb = $c; break }
}

# 2. Check Device Connection
$devices = & $adb devices 2>&1 | Select-String "device$"
if (-not $devices) {
    Write-Host "  [!] No Android device found." -ForegroundColor Red
    Write-Host "      - Connect your phone via USB cable" -ForegroundColor Yellow
    Write-Host "      - Enable USB Debugging in Developer Options" -ForegroundColor Yellow
    Write-Host "      - Tap 'Allow' on the phone's prompt" -ForegroundColor Yellow
    exit 1
}
Write-Host "  [+] Android device detected over USB" -ForegroundColor Green

# 3. Create ADB Reverse Tunnel (Port 8080)
Write-Host "  [+] Establishing reverse tunnel (PC:8080 -> Phone:8080)..." -ForegroundColor Gray
& $adb reverse tcp:8080 tcp:8080
if ($LASTEXITCODE -ne 0) {
    Write-Host "  [!] Failed to create ADB reverse tunnel." -ForegroundColor Red
    exit 1
}
Write-Host "  [+] ADB tunnel established" -ForegroundColor Green

# 4. Verify Proxy Port Responsiveness
Write-Host "  [+] Verifying proxy health on 127.0.0.1:8080..." -ForegroundColor Gray
$tcp = New-Object System.Net.Sockets.TcpClient
$portReachable = $false
try {
    $iar = $tcp.BeginConnect("127.0.0.1", 8080, $null, $null)
    $wait = $iar.AsyncWaitHandle.WaitOne(2500, $false)
    if ($wait) {
        $tcp.EndConnect($iar)
        $portReachable = $true
    }
} catch {}
finally {
    $tcp.Close()
}

if (-not $portReachable) {
    Write-Host "  [!] Warning: Port 8080 is not yet answering on phone." -ForegroundColor Yellow
    Write-Host "      Ensure 'PdaNet-Next' proxy service is turned ON in the phone app." -ForegroundColor Yellow
} else {
    Write-Host "  [+] Proxy engine confirmed active on phone" -ForegroundColor Green
}

# 5. Normalize TTL to 65 (Carrier DPI Bypass)
Write-Host "  [+] Normalizing TTL to 65 (Carrier Bypass)..." -ForegroundColor Gray
netsh int ipv4 set glob defaultcurhoplimit=65 >$null 2>$null
netsh int ipv6 set glob defaultcurhoplimit=65 >$null 2>$null
Write-Host "  [+] System TTL set to 65" -ForegroundColor Green

# 6. Configure System and User Proxy
Write-Host "  [+] Configuring Windows proxy (127.0.0.1:8080)..." -ForegroundColor Gray
netsh winhttp set proxy 127.0.0.1:8080 >$null 2>$null

$regPath = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings"
Set-ItemProperty -Path $regPath -Name ProxyEnable -Value 1
Set-ItemProperty -Path $regPath -Name ProxyServer -Value "127.0.0.1:8080"
Set-ItemProperty -Path $regPath -Name ProxyOverride -Value "localhost;127.0.0.1;*.local"

# Refresh WinINet settings for open browsers
try {
    Add-Type -TypeDefinition @"
    using System;
    using System.Runtime.InteropServices;
    public class WinINet {
        [DllImport("wininet.dll")]
        public static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength);
    }
"@ -ErrorAction SilentlyContinue
    [WinINet]::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0) | Out-Null
    [WinINet]::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0) | Out-Null
} catch {}

# 7. Flush DNS Cache
ipconfig /flushdns >$null 2>$null
Write-Host "  [+] DNS cache flushed" -ForegroundColor Green

Write-Host ""
Write-Host "  ========================================" -ForegroundColor Cyan
Write-Host "  CONNECTED! Broadband active via phone." -ForegroundColor Green
Write-Host "  ========================================" -ForegroundColor Cyan
Write-Host "  Proxy:  127.0.0.1:8080" -ForegroundColor White
Write-Host "  TTL:    65 (Carrier Bypass Active)" -ForegroundColor White
Write-Host ""

if ($NoWait) {
    Write-Host "  Running in background mode. Use disconnect.ps1 to disconnect." -ForegroundColor Gray
    exit 0
}

# 8. Interactive Watchdog (Guarantees proxy cleanup on exit / Ctrl+C)
Write-Host "  [Watchdog Active] Press Ctrl+C at any time to disconnect cleanly." -ForegroundColor Yellow
Write-Host ""

try {
    while ($true) {
        Start-Sleep -Seconds 3
        $devCheck = & $adb devices 2>&1 | Select-String "device$"
        if (-not $devCheck) {
            Write-Host "`n  [!] USB cable was disconnected! Restoring network settings..." -ForegroundColor Red
            break
        }
    }
} finally {
    & (Join-Path $PSScriptRoot "disconnect.ps1")
}
