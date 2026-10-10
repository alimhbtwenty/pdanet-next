# PdaNet-Next: Disconnect (Windows 10/11)
# Restores all network adapter settings, resets proxy, and restores default TTL

param(
    [switch]$Quiet
)

if (-not $Quiet) {
    Write-Host ""
    Write-Host "  PdaNet-Next · Disconnect & Restore" -ForegroundColor Cyan
    Write-Host "  ==================================" -ForegroundColor DarkGray
    Write-Host ""
}

# 1. Reset WinHTTP proxy
if (-not $Quiet) { Write-Host "  [+] Resetting WinHTTP system proxy..." -ForegroundColor Gray }
netsh winhttp reset proxy >$null 2>$null

# 2. Reset User Internet Settings Proxy
if (-not $Quiet) { Write-Host "  [+] Disabling user proxy..." -ForegroundColor Gray }
$regPath = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings"
try {
    Set-ItemProperty -Path $regPath -Name ProxyEnable -Value 0 -ErrorAction SilentlyContinue
    Remove-ItemProperty -Path $regPath -Name ProxyServer -ErrorAction SilentlyContinue
} catch {}

# Refresh WinINet settings for active browsers
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

# 3. Restore Default Windows TTL (128)
if (-not $Quiet) { Write-Host "  [+] Restoring default Windows TTL (128)..." -ForegroundColor Gray }
netsh int ipv4 set glob defaultcurhoplimit=128 >$null 2>$null
netsh int ipv6 set glob defaultcurhoplimit=128 >$null 2>$null

# 4. Flush DNS Cache
if (-not $Quiet) { Write-Host "  [+] Flushing DNS cache..." -ForegroundColor Gray }
ipconfig /flushdns >$null 2>$null

# 5. Remove ADB Reverse Tunnel
if (-not $Quiet) { Write-Host "  [+] Closing ADB reverse tunnels..." -ForegroundColor Gray }
$adbCandidates = @(
    (Join-Path $PSScriptRoot "bin\adb.exe"),
    "$env:ProgramFiles\PdaNet-Next\bin\adb.exe",
    (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
    "adb"
)
foreach ($c in $adbCandidates) {
    if (Get-Command $c -ErrorAction SilentlyContinue -or (Test-Path $c -ErrorAction SilentlyContinue)) {
        try { & $c reverse --remove-all 2>$null; break } catch {}
    }
}

if (-not $Quiet) {
    Write-Host ""
    Write-Host "  [OK] Disconnected cleanly. Normal internet restored." -ForegroundColor Green
    Write-Host ""
}
