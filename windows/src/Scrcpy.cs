using System;
using System.Diagnostics;
using System.IO;

namespace PdaNext
{
    /// <summary>
    /// Wrapper for launching and managing scrcpy instances.
    /// Ensures only one scrcpy window is active at any time.
    /// Sets the ADB environment variable to PdaNext's own adb.exe to prevent
    /// mismatched adb server versions from dropping the USB router link.
    /// </summary>
    public static class Scrcpy
    {
        static readonly object sync = new object();
        static Process currentProcess;
        static string path;
        static readonly System.Text.RegularExpressions.Regex SafeToken =
            new System.Text.RegularExpressions.Regex(@"^[A-Za-z0-9._:\-]+$");
        static readonly System.Text.RegularExpressions.Regex SafePackage =
            new System.Text.RegularExpressions.Regex(@"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$");

        /// <summary>
        /// Locates scrcpy.exe.
        /// In production / releases, scrcpy is bundled in bin\scrcpy\scrcpy.exe.
        /// Falls back to checking local folders and PATH.
        /// </summary>
        public static string ExePath
        {
            get
            {
                if (path != null && File.Exists(path)) return path;
                string dir = AppDomain.CurrentDomain.BaseDirectory;
                string[] candidates = new[]
                {
                    Path.Combine(dir, "bin", "scrcpy", "scrcpy.exe"),
                    Path.Combine(dir, "scrcpy", "scrcpy.exe"),
                    Path.Combine(dir, "bin", "scrcpy.exe"),
                    Path.Combine(dir, "scrcpy.exe")
                };
                foreach (var candidate in candidates)
                {
                    if (File.Exists(candidate)) return path = candidate;
                }
                return path = "scrcpy.exe";
            }
        }

        public static bool IsAvailable
        {
            get
            {
                string p = ExePath;
                if (File.Exists(p)) return true;
                try
                {
                    var res = ProcessRunner.Run("where.exe", "scrcpy.exe", 2000);
                    return res.Ok;
                }
                catch { return false; }
            }
        }

        public static bool IsRunning
        {
            get
            {
                lock (sync)
                {
                    return currentProcess != null && !currentProcess.HasExited;
                }
            }
        }

        /// <summary>
        /// Starts scrcpy with the specified device and optional app package.
        /// Ensures only one scrcpy window is open at a time; any existing instance is terminated.
        /// Explicitly passes the ADB environment variable pointing to PdaNext's own bin\adb.exe.
        /// </summary>
        public static Process Start(string serial, string package = null, string appLabel = null)
        {
            lock (sync)
            {
                StopInternal();

                string args = BuildArguments(serial, package, appLabel);
                var psi = new ProcessStartInfo(ExePath, args)
                {
                    UseShellExecute = false,
                    CreateNoWindow = true,
                };

                // CRITICAL: Point ADB to PdaNext's adb.exe so scrcpy does not start a differing
                // adb server version that kills PdaNext's adb server and drops the USB link.
                string adbExe = Adb.ExePath;
                if (!string.IsNullOrEmpty(adbExe))
                {
                    psi.EnvironmentVariables["ADB"] = adbExe;
                }

                try
                {
                    var p = new Process { StartInfo = psi, EnableRaisingEvents = true };
                    p.Exited += (s, e) =>
                    {
                        lock (sync)
                        {
                            if (currentProcess == p) currentProcess = null;
                        }
                    };
                    p.Start();
                    currentProcess = p;
                    return p;
                }
                catch (Exception)
                {
                    currentProcess = null;
                    throw;
                }
            }
        }

        /// <summary>
        /// Builds the scrcpy command line arguments:
        /// --serial <phone> [--start-app=<package>] --window-title "<title>" --stay-awake
        /// </summary>
        public static string BuildArguments(string serial, string package = null, string appLabel = null)
        {
            var sb = new System.Text.StringBuilder();

            // Serial, package and label come from the phone: keep them from injecting extra arguments.
            if (!string.IsNullOrWhiteSpace(serial) && SafeToken.IsMatch(serial.Trim()))
            {
                sb.Append("--serial ").Append(serial.Trim()).Append(" ");
            }

            if (!string.IsNullOrWhiteSpace(package) && SafePackage.IsMatch(package.Trim()))
            {
                sb.Append("--start-app=").Append(package.Trim()).Append(" ");
            }

            string label = appLabel == null ? null : System.Text.RegularExpressions.Regex.Replace(appLabel, "[\"\\\\\\r\\n]", "").Trim();
            string title = !string.IsNullOrWhiteSpace(label)
                ? $"{label} - PdaNext"
                : "Phone - PdaNext";

            sb.Append("--window-title \"").Append(title).Append("\" ");
            sb.Append("--stay-awake");

            return sb.ToString().Trim();
        }

        /// <summary>
        /// Stops the currently running scrcpy process, if any.
        /// Closing or stopping scrcpy changes nothing else in PdaNext.
        /// </summary>
        public static void Stop()
        {
            lock (sync)
            {
                StopInternal();
            }
        }

        static void StopInternal()
        {
            if (currentProcess != null)
            {
                try
                {
                    if (!currentProcess.HasExited)
                    {
                        currentProcess.Kill();
                        currentProcess.WaitForExit(1000);
                    }
                }
                catch { }
                finally
                {
                    try { currentProcess.Dispose(); } catch { }
                    currentProcess = null;
                }
            }
        }
    }
}
