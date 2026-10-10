using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;

namespace PdaNetNext
{
    public sealed class AdbDevice
    {
        public string Serial;
        /// <summary>"device" when ready; also "unauthorized", "offline", "no permissions"...</summary>
        public string State;
        public string Model;

        public bool Ready => State == "device";
        public string DisplayName => string.IsNullOrEmpty(Model) ? Serial : Model.Replace('_', ' ');
    }

    public sealed class ProcessResult
    {
        public int ExitCode;
        public string Output;
        public bool TimedOut;
        public bool Ok => ExitCode == 0 && !TimedOut;
    }

    /// <summary>Thin wrapper over the bundled adb.exe.</summary>
    public static class Adb
    {
        public const string PhonePackage = "io.github.alimhbtwenty.pdanetnext";

        static string path;

        public static string ExePath
        {
            get
            {
                if (path != null) return path;
                string dir = AppDomain.CurrentDomain.BaseDirectory;
                foreach (var candidate in new[] { Path.Combine(dir, "bin", "adb.exe"), Path.Combine(dir, "adb.exe") })
                {
                    if (File.Exists(candidate)) return path = candidate;
                }
                return path = "adb.exe"; // from PATH
            }
        }

        public static ProcessResult Run(string args, int timeoutMs = 15000) => ProcessRunner.Run(ExePath, args, timeoutMs);

        public static ProcessResult Run(string serial, string args, int timeoutMs = 15000) =>
            Run("-s " + serial + " " + args, timeoutMs);

        public static List<AdbDevice> Devices()
        {
            var r = Run("devices -l", 10000);
            return r.Ok ? ParseDevices(r.Output) : new List<AdbDevice>();
        }

        /// <summary>Parses `adb devices -l`. Emulators are skipped: they have no phone uplink to share.</summary>
        public static List<AdbDevice> ParseDevices(string output)
        {
            var list = new List<AdbDevice>();
            foreach (var raw in output.Split('\n'))
            {
                string line = raw.Trim();
                if (line.Length == 0 || line.StartsWith("List of devices") || line.StartsWith("*")) continue;
                var parts = line.Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length < 2) continue;
                var dev = new AdbDevice { Serial = parts[0], State = parts[1] };
                // "no permissions (...)" spans several words; keep the state readable.
                if (parts[1] == "no") dev.State = "no permissions";
                foreach (var p in parts)
                {
                    if (p.StartsWith("model:")) dev.Model = p.Substring(6);
                }
                if (dev.Serial.StartsWith("emulator-")) continue;
                list.Add(dev);
            }
            return list;
        }
    }

    public static class ProcessRunner
    {
        public static ProcessResult Run(string exe, string args, int timeoutMs)
        {
            var psi = new ProcessStartInfo(exe, args)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8,
            };
            var output = new StringBuilder();
            try
            {
                using (var p = new Process { StartInfo = psi })
                {
                    p.OutputDataReceived += (s, e) => { if (e.Data != null) lock (output) output.AppendLine(e.Data); };
                    p.ErrorDataReceived += (s, e) => { if (e.Data != null) lock (output) output.AppendLine(e.Data); };
                    p.Start();
                    p.BeginOutputReadLine();
                    p.BeginErrorReadLine();
                    if (!p.WaitForExit(timeoutMs))
                    {
                        try { p.Kill(); } catch { }
                        return new ProcessResult { ExitCode = -1, TimedOut = true, Output = output.ToString() };
                    }
                    p.WaitForExit(); // flush async readers
                    lock (output) return new ProcessResult { ExitCode = p.ExitCode, Output = output.ToString() };
                }
            }
            catch (Exception e)
            {
                return new ProcessResult { ExitCode = -1, Output = e.Message };
            }
        }
    }
}
