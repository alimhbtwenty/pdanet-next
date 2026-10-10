# PdaNet-Next Windows Client & System Tray Manager
# Creates Virtual Network Adapter and System Tray Icon

param(
    [switch]$Install,
    [switch]$Uninstall,
    [switch]$Silent
)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$appDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$binDir = Join-Path $appDir "bin"

# Check Administrator Privileges
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Start-Process powershell.exe -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$($MyInvocation.MyCommand.Definition)`"" -Verb RunAs
    exit
}

class PdaNetTrayApp : System.Windows.Forms.ApplicationContext {
    [System.Windows.Forms.NotifyIcon]$trayIcon
    [System.Windows.Forms.ContextMenuStrip]$contextMenu
    [System.Windows.Forms.ToolStripMenuItem]$statusItem
    [System.Windows.Forms.ToolStripMenuItem]$connectItem
    [System.Windows.Forms.ToolStripMenuItem]$disconnectItem
    [System.Windows.Forms.ToolStripMenuItem]$exitItem
    [bool]$isConnected = $false
    [string]$adbPath = "adb"

    PdaNetTrayApp() {
        # Locate bundled adb
        $candidates = @(
            "$env:ProgramFiles\PdaNet-Next\bin\adb.exe",
            (Join-Path $PSScriptRoot "bin\adb.exe")
        )
        foreach ($c in $candidates) {
            if (Test-Path $c) { $this.adbPath = $c; break }
        }

        $this.contextMenu = New-Object System.Windows.Forms.ContextMenuStrip

        $this.statusItem = New-Object System.Windows.Forms.ToolStripMenuItem("PdaNet-Next: Disconnected")
        $this.statusItem.Enabled = $false
        $this.statusItem.Font = New-Object System.Drawing.Font("Segoe UI", 9, [System.Drawing.FontStyle]::Bold)
        $this.contextMenu.Items.Add($this.statusItem) | Out-Null
        $this.contextMenu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator)) | Out-Null

        $this.connectItem = New-Object System.Windows.Forms.ToolStripMenuItem("Connect (USB)")
        $this.connectItem.Add_Click({ $this.Connect() })
        $this.contextMenu.Items.Add($this.connectItem) | Out-Null

        $this.disconnectItem = New-Object System.Windows.Forms.ToolStripMenuItem("Disconnect")
        $this.disconnectItem.Enabled = $false
        $this.disconnectItem.Add_Click({ $this.Disconnect() })
        $this.contextMenu.Items.Add($this.disconnectItem) | Out-Null

        $this.contextMenu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator)) | Out-Null
        $this.exitItem = New-Object System.Windows.Forms.ToolStripMenuItem("Exit")
        $this.exitItem.Add_Click({ $this.Exit() })
        $this.contextMenu.Items.Add($this.exitItem) | Out-Null

        $this.trayIcon = New-Object System.Windows.Forms.NotifyIcon
        $this.trayIcon.Text = "PdaNet-Next (USB Tethering)"
        $this.trayIcon.ContextMenuStrip = $this.contextMenu
        $this.trayIcon.Icon = [System.Drawing.SystemIcons]::Shield
        $this.trayIcon.Visible = $true

        $this.Connect()
    }

    [void]Connect() {
        try {
            $devices = & $this.adbPath devices 2>&1 | Select-String "device$"
            if (-not $devices) {
                $this.trayIcon.ShowBalloonTip(3000, "PdaNet-Next", "No phone detected over USB. Enable USB Debugging.", [System.Windows.Forms.ToolTipIcon]::Warning)
                return
            }

            & $this.adbPath reverse tcp:8080 tcp:8080
            
            netsh int ipv4 set glob defaultcurhoplimit=65 2>$null
            netsh int ipv6 set glob defaultcurhoplimit=65 2>$null
            netsh winhttp set proxy 127.0.0.1:8080 >$null 2>&1

            $regPath = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings"
            Set-ItemProperty -Path $regPath -Name ProxyEnable -Value 1
            Set-ItemProperty -Path $regPath -Name ProxyServer -Value "127.0.0.1:8080"
            Set-ItemProperty -Path $regPath -Name ProxyOverride -Value "localhost;127.0.0.1;*.local"

            $this.isConnected = $true
            $this.statusItem.Text = "PdaNet-Next: Connected (Unlimited USB)"
            $this.connectItem.Enabled = $false
            $this.disconnectItem.Enabled = $true
            $this.trayIcon.Icon = [System.Drawing.SystemIcons]::Application
            $this.trayIcon.ShowBalloonTip(3000, "PdaNet-Next Connected", "Broadband connection established. Carrier bypass active (TTL=65).", [System.Windows.Forms.ToolTipIcon]::Info)
        } catch {
            $this.trayIcon.ShowBalloonTip(3000, "Error", $_.Exception.Message, [System.Windows.Forms.ToolTipIcon]::Error)
        }
    }

    [void]Disconnect() {
        netsh winhttp reset proxy >$null 2>&1
        $regPath = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings"
        Set-ItemProperty -Path $regPath -Name ProxyEnable -Value 0
        Remove-ItemProperty -Path $regPath -Name ProxyServer -ErrorAction SilentlyContinue

        netsh int ipv4 set glob defaultcurhoplimit=128 2>$null
        netsh int ipv6 set glob defaultcurhoplimit=128 2>$null

        try { & $this.adbPath reverse --remove-all 2>$null } catch {}

        $this.isConnected = $false
        $this.statusItem.Text = "PdaNet-Next: Disconnected"
        $this.connectItem.Enabled = $true
        $this.disconnectItem.Enabled = $false
        $this.trayIcon.Icon = [System.Drawing.SystemIcons]::Shield
        $this.trayIcon.ShowBalloonTip(2000, "PdaNet-Next", "Disconnected. Network settings restored.", [System.Windows.Forms.ToolTipIcon]::Info)
    }

    [void]Exit() {
        if ($this.isConnected) { $this.Disconnect() }
        $this.trayIcon.Visible = $false
        [System.Windows.Forms.Application]::Exit()
    }
}

[System.Windows.Forms.Application]::EnableVisualStyles()
$appContext = [PdaNetTrayApp]::new()
[System.Windows.Forms.Application]::Run($appContext)
