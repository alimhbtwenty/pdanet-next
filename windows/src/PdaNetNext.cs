using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Windows.Forms;
using Microsoft.Win32;

namespace PdaNetNext
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new PdaNetTrayContext());
        }
    }

    public class PdaNetTrayContext : ApplicationContext
    {
        private NotifyIcon trayIcon;
        private ContextMenuStrip contextMenu;
        private ToolStripMenuItem statusItem;
        private ToolStripMenuItem connectItem;
        private ToolStripMenuItem disconnectItem;
        private ToolStripMenuItem exitItem;
        private bool isConnected = false;
        private string appDir;
        private string adbPath;

        public PdaNetTrayContext()
        {
            appDir = AppDomain.CurrentDomain.BaseDirectory;
            adbPath = Path.Combine(appDir, "bin", "adb.exe");
            if (!File.Exists(adbPath))
            {
                adbPath = "adb.exe"; // fallback to PATH
            }

            contextMenu = new ContextMenuStrip();

            statusItem = new ToolStripMenuItem("PdaNet-Next: Disconnected") { Enabled = false };
            statusItem.Font = new Font("Segoe UI", 9, FontStyle.Bold);
            contextMenu.Items.Add(statusItem);
            contextMenu.Items.Add(new ToolStripSeparator());

            connectItem = new ToolStripMenuItem("Connect (USB)", null, (s, e) => Connect());
            contextMenu.Items.Add(connectItem);

            disconnectItem = new ToolStripMenuItem("Disconnect", null, (s, e) => Disconnect()) { Enabled = false };
            contextMenu.Items.Add(disconnectItem);

            contextMenu.Items.Add(new ToolStripSeparator());

            exitItem = new ToolStripMenuItem("Exit", null, (s, e) => Exit());
            contextMenu.Items.Add(exitItem);

            trayIcon = new NotifyIcon
            {
                Text = "PdaNet-Next (USB Tethering)",
                ContextMenuStrip = contextMenu,
                Icon = SystemIcons.Shield,
                Visible = true
            };

            trayIcon.DoubleClick += (s, e) => {
                if (isConnected) Disconnect(); else Connect();
            };

            // Auto-connect attempt on start
            Connect();
        }

        private void RunCmd(string cmd, string args)
        {
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo(cmd, args)
                {
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true
                };
                using (Process p = Process.Start(psi))
                {
                    if (p != null) p.WaitForExit(3000);
                }
            }
            catch { }
        }

        private string RunCmdOutput(string cmd, string args)
        {
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo(cmd, args)
                {
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    RedirectStandardOutput = true
                };
                using (Process p = Process.Start(psi))
                {
                    string res = p.StandardOutput.ReadToEnd();
                    p.WaitForExit(3000);
                    return res;
                }
            }
            catch { return ""; }
        }

        private void Connect()
        {
            try
            {
                string devices = RunCmdOutput(adbPath, "devices");
                if (!devices.Contains("\tdevice"))
                {
                    trayIcon.ShowBalloonTip(3000, "PdaNet-Next", "No phone detected over USB. Make sure USB Debugging is ON.", ToolTipIcon.Warning);
                    return;
                }

                // 1. Setup ADB reverse port forward
                RunCmd(adbPath, "reverse tcp:8080 tcp:8080");

                // 2. Normalize Windows TTL to 65 (Carrier Bypass)
                RunCmd("netsh", "int ipv4 set glob defaultcurhoplimit=65");
                RunCmd("netsh", "int ipv6 set glob defaultcurhoplimit=65");

                // 3. Set System WinHTTP proxy
                RunCmd("netsh", "winhttp set proxy 127.0.0.1:8080");

                // 4. Set Internet Settings Proxy for all Windows apps & browsers
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Internet Settings", true))
                {
                    if (key != null)
                    {
                        key.SetValue("ProxyEnable", 1);
                        key.SetValue("ProxyServer", "127.0.0.1:8080");
                        key.SetValue("ProxyOverride", "localhost;127.0.0.1;*.local");
                    }
                }

                isConnected = true;
                statusItem.Text = "PdaNet-Next: Connected (Unlimited)";
                connectItem.Enabled = false;
                disconnectItem.Enabled = true;
                trayIcon.Icon = SystemIcons.Application;
                trayIcon.ShowBalloonTip(3000, "PdaNet-Next Connected", "USB broadband active. All Windows traffic routed through phone (TTL=65).", ToolTipIcon.Info);
            }
            catch (Exception ex)
            {
                trayIcon.ShowBalloonTip(3000, "Error", ex.Message, ToolTipIcon.Error);
            }
        }

        private void Disconnect()
        {
            try
            {
                // Reset WinHTTP proxy
                RunCmd("netsh", "winhttp reset proxy");

                // Reset Registry Proxy
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Internet Settings", true))
                {
                    if (key != null)
                    {
                        key.SetValue("ProxyEnable", 0);
                        key.DeleteValue("ProxyServer", false);
                    }
                }

                // Restore default TTL
                RunCmd("netsh", "int ipv4 set glob defaultcurhoplimit=128");
                RunCmd("netsh", "int ipv6 set glob defaultcurhoplimit=128");

                // Remove ADB reverse
                RunCmd(adbPath, "reverse --remove-all");

                isConnected = false;
                statusItem.Text = "PdaNet-Next: Disconnected";
                connectItem.Enabled = true;
                disconnectItem.Enabled = false;
                trayIcon.Icon = SystemIcons.Shield;
                trayIcon.ShowBalloonTip(2000, "PdaNet-Next", "Disconnected. Network settings restored.", ToolTipIcon.Info);
            }
            catch { }
        }

        private void Exit()
        {
            if (isConnected) Disconnect();
            trayIcon.Visible = false;
            Application.Exit();
        }
    }
}
