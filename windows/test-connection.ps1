# PdaNet-Next: Diagnostic & Healthcheck Tool
# Verifies proxy tunnel, TTL hoplimit, and connection latency

Write-Host ""
Write-Host "  PdaNet-Next · System Diagnostics" -ForegroundColor Cyan
Write-Host "  ================================" -ForegroundColor DarkGray
Write-Host ""

# 1. Admin Privileges
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($isAdmin) {
    Write-Host "  [OK] Process running with Administrator privileges" -ForegroundColor Green
} else {
    Write-Host "  [WARN] Not running as Administrator (some settings may be read-only)" -ForegroundColor Yellow
}

# 2. ADB Detection
$adb = "adb"
$candidates = @(
    (Join-Path $PSScriptRoot "bin\adb.exe"),
    "$env:ProgramFiles\PdaNet-Next\bin\adb.exe",
    (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
)
foreach ($c in $candidates) {
    if (Test-Path $c) { $adb = $c; break }
}

$adbFound = $false
if (Test-Path $adb -ErrorAction SilentlyContinue) {
    $adbFound = $true
} elseif (Get-Command $adb -ErrorAction SilentlyContinue) {
    $adbFound = $true
}

if ($adbFound) {
    Write-Host "  [OK] ADB binary located: $adb" -ForegroundColor Green
    $devs = & $adb devices 2>&1 | Select-String "device$"
    if ($devs) {
        Write-Host "  [OK] Android phone detected over USB: $($devs.Count) device(s)" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] No Android device connected with USB debugging enabled" -ForegroundColor Red
    }
} else {
    Write-Host "  [FAIL] ADB binary not found" -ForegroundColor Red
}

# 3. Proxy Port 8080 Test
Write-Host "`n  Testing Proxy Port (127.0.0.1:8080)..." -ForegroundColor Gray
$tcp = New-Object System.Net.Sockets.TcpClient
try {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $iar = $tcp.BeginConnect("127.0.0.1", 8080, $null, $null)
    $wait = $iar.AsyncWaitHandle.WaitOne(2000, $false)
    $sw.Stop()
    if ($wait) {
        $tcp.EndConnect($iar)
        Write-Host "  [OK] Local proxy responding in $($sw.ElapsedMilliseconds) ms" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] Port 8080 not listening. Is PdaNet-Next active on the phone?" -ForegroundColor Red
    }
} catch {
    Write-Host "  [FAIL] Could not connect to 127.0.0.1:8080 ($($_.Exception.Message))" -ForegroundColor Red
} finally {
    $tcp.Close()
}

# 4. TTL Hoplimit Verification
Write-Host "`n  Checking TTL Hoplimit (Carrier Bypass)..." -ForegroundColor Gray
$v4raw = (netsh int ipv4 show glob | Out-String)
$hopV4 = ""
$match = [regex]::Match($v4raw, "(?:Default CurHopLimit|hop limit|جهش).*?:\s*(\d+)", [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
if ($match.Success) {
    $hopV4 = $match.Groups[1].Value
} else {
    $m2 = [regex]::Match($v4raw, ":\s*(\d+)")
    if ($m2.Success) { $hopV4 = $m2.Groups[1].Value }
}

Write-Host "  [INFO] Current IPv4 TTL: $hopV4" -ForegroundColor White
if ($hopV4 -eq "65") {
    Write-Host "  [OK] TTL is normalized to 65 (Carrier DPI limits bypassed)" -ForegroundColor Green
} elseif ($hopV4 -eq "128") {
    Write-Host "  [INFO] TTL is standard Windows default (128)" -ForegroundColor Gray
} else {
    Write-Host "  [WARN] Unexpected TTL: $hopV4" -ForegroundColor Yellow
}

# 5. Windows Proxy Status
Write-Host "`n  Checking Windows Proxy Status..." -ForegroundColor Gray
$regPath = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings"
$pEnable = (Get-ItemProperty -Path $regPath -Name ProxyEnable -ErrorAction SilentlyContinue).ProxyEnable
$pServer = (Get-ItemProperty -Path $regPath -Name ProxyServer -ErrorAction SilentlyContinue).ProxyServer
if ($pEnable -eq 1) {
    Write-Host "  [OK] User Proxy is ENABLED -> $pServer" -ForegroundColor Green
} else {
    Write-Host "  [INFO] User Proxy is DISABLED (Standard direct connection)" -ForegroundColor Gray
}

Write-Host ""
Write-Host "  Diagnostics complete." -ForegroundColor Cyan
Write-Host ""
