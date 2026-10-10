using System;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace PdaNetNext
{
    /// <summary>
    /// "Browsers only" mode: points the Windows (WinINet) proxy at the phone relay.
    /// The user's previous settings are saved first and put back exactly on disconnect,
    /// or on the next start if the app was killed while connected.
    /// </summary>
    public static class SystemProxy
    {
        const string InternetSettings = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";
        const string BackupKey = AppSettings.RegistryRoot + @"\ProxyBackup";
        static readonly string[] Values = { "ProxyEnable", "ProxyServer", "ProxyOverride", "AutoConfigURL" };

        public static bool HasBackup
        {
            get { using (var k = Registry.CurrentUser.OpenSubKey(BackupKey)) return k != null; }
        }

        public static void Apply(int port)
        {
            if (!HasBackup) Backup();
            using (var k = Registry.CurrentUser.CreateSubKey(InternetSettings))
            {
                k.SetValue("ProxyEnable", 1, RegistryValueKind.DWord);
                k.SetValue("ProxyServer", "127.0.0.1:" + port, RegistryValueKind.String);
                k.SetValue("ProxyOverride", "localhost;127.*;10.*;172.16.*;192.168.*;<local>", RegistryValueKind.String);
                k.DeleteValue("AutoConfigURL", false);
            }
            Refresh();
        }

        public static void Restore()
        {
            if (!HasBackup) return;
            using (var backup = Registry.CurrentUser.OpenSubKey(BackupKey))
            using (var k = Registry.CurrentUser.CreateSubKey(InternetSettings))
            {
                foreach (var name in Values)
                {
                    object saved = backup.GetValue(name);
                    if (saved == null) k.DeleteValue(name, false);
                    else k.SetValue(name, saved, backup.GetValueKind(name));
                }
            }
            Registry.CurrentUser.DeleteSubKeyTree(BackupKey, false);
            Refresh();
        }

        static void Backup()
        {
            using (var k = Registry.CurrentUser.OpenSubKey(InternetSettings))
            using (var backup = Registry.CurrentUser.CreateSubKey(BackupKey))
            {
                foreach (var name in Values)
                {
                    object v = k?.GetValue(name);
                    if (v != null) backup.SetValue(name, v, k.GetValueKind(name));
                }
            }
        }

        [DllImport("wininet.dll", SetLastError = true)]
        static extern bool InternetSetOption(IntPtr hInternet, int option, IntPtr buffer, int length);

        static void Refresh()
        {
            const int SettingsChanged = 39, Refresh = 37;
            InternetSetOption(IntPtr.Zero, SettingsChanged, IntPtr.Zero, 0);
            InternetSetOption(IntPtr.Zero, Refresh, IntPtr.Zero, 0);
        }
    }
}
