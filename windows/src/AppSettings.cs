using System;
using System.Diagnostics;
using Microsoft.Win32;

namespace PdaNext
{
    /// <summary>User preferences, stored under HKCU\Software\PdaNext.</summary>
    public static class AppSettings
    {
        public const string RegistryRoot = @"Software\PdaNext";
        public const int RelayPort = 28080;
        const string TaskName = "PdaNext";

        public static bool AutoConnect
        {
            get => GetInt("AutoConnect", 1) == 1;
            set => SetInt("AutoConnect", value ? 1 : 0);
        }

        /// <summary>Send every DNS query to the phone (NRPT rule), not also to the PC's other networks.</summary>
        public static bool PreventDnsLeaks
        {
            get => GetInt("PreventDnsLeaks", 1) == 1;
            set => SetInt("PreventDnsLeaks", value ? 1 : 0);
        }

        /// <summary>Mode of a session that was never cleanly closed, or null. Used to repair after a crash.</summary>
        public static string ActiveSession
        {
            get { using (var k = Registry.CurrentUser.OpenSubKey(RegistryRoot)) return k?.GetValue("ActiveSession") as string; }
            set
            {
                using (var k = Registry.CurrentUser.CreateSubKey(RegistryRoot))
                {
                    if (value == null) k.DeleteValue("ActiveSession", false);
                    else k.SetValue("ActiveSession", value);
                }
            }
        }

        /// <summary>
        /// Start at logon, elevated, without a UAC prompt each time: a scheduled task with highest privileges.
        /// </summary>
        public static bool StartWithWindows
        {
            get => ProcessRunner.Run("schtasks.exe", "/Query /TN \"" + TaskName + "\"", 5000).Ok;
            set
            {
                if (value)
                {
                    string exe = Process.GetCurrentProcess().MainModule.FileName;
                    ProcessRunner.Run("schtasks.exe",
                        "/Create /F /TN \"" + TaskName + "\" /SC ONLOGON /RL HIGHEST /TR \"\\\"" + exe + "\\\" --tray\"", 10000);
                }
                else
                {
                    ProcessRunner.Run("schtasks.exe", "/Delete /F /TN \"" + TaskName + "\"", 10000);
                }
            }
        }

        static int GetInt(string name, int fallback)
        {
            try
            {
                using (var k = Registry.CurrentUser.OpenSubKey(RegistryRoot))
                    return k?.GetValue(name) is int v ? v : fallback;
            }
            catch (Exception) { return fallback; }
        }

        static void SetInt(string name, int value)
        {
            using (var k = Registry.CurrentUser.CreateSubKey(RegistryRoot)) k.SetValue(name, value, RegistryValueKind.DWord);
        }
    }
}
