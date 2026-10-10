# PdaNet-Next: Disconnect (Windows 11)
# Run as Administrator

Write-Host ""
Write-Host "  PdaNet-Next · Disconnect" -ForegroundColor Cyan
Write-Host "  ========================" -ForegroundColor DarkGray
Write-Host ""

# 1. Remove system proxy
Write-Host "  [+] Removing system proxy..." -ForegroundColor Gray
netsh winhttp reset proxy >$null 2>$null

$regPath = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings"
Set-ItemProperty -Path $regPath -Name ProxyEnable -Value 0
Remove-ItemProperty -Path $regPath -Name ProxyServer -ErrorAction SilentlyContinue

# 2. Restore default TTL
Write-Host "  [+] Restoring default TTL (128)..." -ForegroundColor Gray
netsh int ipv4 set glob defaultcurhoplimit=128 2>$null
netsh int ipv6 set glob defaultcurhoplimit=128 2>$null

# 3. Remove ADB tunnel
Write-Host "  [+] Removing ADB tunnel..." -ForegroundColor Gray
$adb = "adb"
try {
    & $adb reverse --remove-all 2>$null
} catch {
    $ptPath = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $ptPath) { & $ptPath reverse --remove-all 2>$null }
}

Write-Host ""
Write-Host "  Disconnected. System proxy and TTL restored." -ForegroundColor Green
Write-Host ""
