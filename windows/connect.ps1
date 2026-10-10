# PdaNet-Next: Connect (Windows 11)
# Run as Administrator

$ErrorActionPreference = "Stop"
$adb = "adb"

Write-Host ""
Write-Host "  PdaNet-Next · USB Connect" -ForegroundColor Cyan
Write-Host "  =========================" -ForegroundColor DarkGray
Write-Host ""

# 1. Check ADB
try {
    $null = & $adb version 2>$null
} catch {
    # Try platform-tools in user profile
    $ptPath = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $ptPath) { $adb = $ptPath }
    else {
        Write-Host "  [!] ADB not found. Install Android Platform Tools first:" -ForegroundColor Red
        Write-Host "      https://developer.android.com/tools/releases/platform-tools" -ForegroundColor Yellow
        Write-Host "      Or run:  .\setup-adb.ps1" -ForegroundColor Yellow
        exit 1
    }
}

# 2. Check device connected
$devices = & $adb devices 2>&1 | Select-String "device$"
if (-not $devices) {
    Write-Host "  [!] No Android device found." -ForegroundColor Red
    Write-Host "      - Connect your phone via USB cable" -ForegroundColor Yellow
    Write-Host "      - Enable USB Debugging in Developer Options" -ForegroundColor Yellow
    Write-Host "      - Tap 'Allow' on the phone's USB debugging prompt" -ForegroundColor Yellow
    exit 1
}
Write-Host "  [+] Device connected" -ForegroundColor Green

# 3. Set up ADB reverse tunnel
Write-Host "  [+] Creating ADB reverse tunnel (port 8080)..." -ForegroundColor Gray
& $adb reverse tcp:8080 tcp:8080
if ($LASTEXITCODE -ne 0) {
    Write-Host "  [!] ADB reverse failed. Is PdaNet-Next running on the phone?" -ForegroundColor Red
    exit 1
}
Write-Host "  [+] Tunnel established: localhost:8080 -> phone:8080" -ForegroundColor Green

# 4. Normalize TTL (prevents carrier detection)
Write-Host "  [+] Normalizing TTL to 65 (carrier bypass)..." -ForegroundColor Gray
netsh int ipv4 set glob defaultcurhoplimit=65 2>$null
netsh int ipv6 set glob defaultcurhoplimit=65 2>$null
Write-Host "  [+] TTL set to 65" -ForegroundColor Green

# 5. Set system proxy
Write-Host "  [+] Setting system proxy to localhost:8080..." -ForegroundColor Gray
netsh winhttp set proxy 127.0.0.1:8080 >$null

# 6. Also set for current user (browser/apps pick this up)
$regPath = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings"
Set-ItemProperty -Path $regPath -Name ProxyEnable -Value 1
Set-ItemProperty -Path $regPath -Name ProxyServer -Value "127.0.0.1:8080"
Set-ItemProperty -Path $regPath -Name ProxyOverride -Value "localhost;127.0.0.1;*.local"

Write-Host ""
Write-Host "  ========================================" -ForegroundColor Cyan
Write-Host "  CONNECTED! You're online via your phone." -ForegroundColor Green
Write-Host "  ========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Proxy:  127.0.0.1:8080" -ForegroundColor White
Write-Host "  TTL:    65 (carrier-safe)" -ForegroundColor White
Write-Host "  DNS:    Encrypted via phone (DoH)" -ForegroundColor White
Write-Host ""
Write-Host "  To disconnect: .\disconnect.ps1" -ForegroundColor DarkGray
Write-Host ""
