# PdaNet-Next: Connect (Windows 11)
# Run as Administrator

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "  PdaNet-Next · USB Connect" -ForegroundColor Cyan
Write-Host "  =========================" -ForegroundColor DarkGray
Write-Host ""

# Find ADB: either bundled in bin, installed in Program Files, or in PATH
$adb = "adb"
$candidates = @(
    (Join-Path $PSScriptRoot "bin\adb.exe"),
    "$env:ProgramFiles\PdaNet-Next\bin\adb.exe",
    (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
)
foreach ($c in $candidates) {
    if (Test-Path $c) { $adb = $c; break }
}

# 1. Check device connected
$devices = & $adb devices 2>&1 | Select-String "device$"
if (-not $devices) {
    Write-Host "  [!] No Android device found." -ForegroundColor Red
    Write-Host "      - Connect your phone via USB cable" -ForegroundColor Yellow
    Write-Host "      - Enable USB Debugging in Developer Options" -ForegroundColor Yellow
    Write-Host "      - Tap 'Allow' on the phone's USB debugging prompt" -ForegroundColor Yellow
    exit 1
}
Write-Host "  [+] Device connected" -ForegroundColor Green

# 2. Set up ADB reverse tunnel
Write-Host "  [+] Creating ADB reverse tunnel (port 8080)..." -ForegroundColor Gray
& $adb reverse tcp:8080 tcp:8080
if ($LASTEXITCODE -ne 0) {
    Write-Host "  [!] ADB reverse failed. Is PdaNet-Next running on the phone?" -ForegroundColor Red
    exit 1
}
Write-Host "  [+] Tunnel established: localhost:8080 -> phone:8080" -ForegroundColor Green

# 3. Normalize TTL (prevents carrier detection)
Write-Host "  [+] Normalizing TTL to 65 (carrier bypass)..." -ForegroundColor Gray
netsh int ipv4 set glob defaultcurhoplimit=65 2>$null
netsh int ipv6 set glob defaultcurhoplimit=65 2>$null
Write-Host "  [+] TTL set to 65" -ForegroundColor Green

# 4. Set system proxy
Write-Host "  [+] Setting system proxy to localhost:8080..." -ForegroundColor Gray
netsh winhttp set proxy 127.0.0.1:8080 >$null

# 5. Set for current user
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
Write-Host ""
