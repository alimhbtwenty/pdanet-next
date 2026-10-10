# PdaNet-Next: Install ADB (Windows 11)
# Downloads official Android Platform Tools if ADB is not installed

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "  PdaNet-Next · ADB Setup" -ForegroundColor Cyan
Write-Host ""

# Check if already available
try {
    $v = & adb version 2>$null
    if ($v) {
        Write-Host "  [+] ADB is already installed:" -ForegroundColor Green
        Write-Host "      $v" -ForegroundColor Gray
        exit 0
    }
} catch {}

$installDir = Join-Path $env:LOCALAPPDATA "PdaNet-Next\platform-tools"
$zipPath    = Join-Path $env:TEMP "platform-tools.zip"
$url        = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"

Write-Host "  [+] Downloading Android Platform Tools..." -ForegroundColor Gray
Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing

Write-Host "  [+] Extracting to $installDir ..." -ForegroundColor Gray
if (Test-Path $installDir) { Remove-Item $installDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Split-Path $installDir) | Out-Null
Expand-Archive -Path $zipPath -DestinationPath (Split-Path $installDir) -Force
Remove-Item $zipPath -Force

# Add to user PATH
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($userPath -notlike "*$installDir*") {
    [Environment]::SetEnvironmentVariable("Path", "$installDir;$userPath", "User")
    $env:Path = "$installDir;$env:Path"
    Write-Host "  [+] Added to PATH" -ForegroundColor Green
}

Write-Host ""
$adbExe = Join-Path $installDir "adb.exe"
& $adbExe version
Write-Host ""
Write-Host "  [+] ADB installed successfully!" -ForegroundColor Green
Write-Host "      Restart your terminal, then run: .\connect.ps1" -ForegroundColor Yellow
Write-Host ""
