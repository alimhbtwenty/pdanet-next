using System;
using System.Globalization;
using System.IO;

namespace PdaNext
{
    public static class FileLogger
    {
        static readonly object sync = new object();
        const long MaxSizeBytes = 2 * 1024 * 1024; // 2 MB
        const int RetentionDays = 7;
        static DateTime lastClean = DateTime.MinValue;

        public static string LogDirectory => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "PdaNext",
            "logs"
        );

        public static void Log(string line)
        {
            if (line == null) return;
            string redacted = SecretRedactor.Redact(line);
            lock (sync)
            {
                try
                {
                    string dir = LogDirectory;
                    if (!Directory.Exists(dir)) Directory.CreateDirectory(dir);

                    if (DateTime.UtcNow - lastClean > TimeSpan.FromHours(1))
                    {
                        lastClean = DateTime.UtcNow;
                        CleanOldLogs(dir);
                    }

                    string today = DateTime.UtcNow.ToString("yyyyMMdd", CultureInfo.InvariantCulture);
                    string targetFile = GetCurrentLogFile(dir, today);

                    // Check if current file + line exceeds 2 MB
                    if (File.Exists(targetFile) && new FileInfo(targetFile).Length + redacted.Length + 2 > MaxSizeBytes)
                    {
                        targetFile = RotateFile(dir, today);
                    }

                    using (var writer = new StreamWriter(targetFile, true, System.Text.Encoding.UTF8))
                    {
                        writer.WriteLine(redacted);
                    }
                }
                catch
                {
                    // Logging should never crash the main application
                }
            }
        }

        static string GetCurrentLogFile(string dir, string dateStr)
        {
            string baseFile = Path.Combine(dir, "pdanext-" + dateStr + ".log");
            if (!File.Exists(baseFile)) return baseFile;

            // Check if there are existing rotation parts
            int part = 1;
            while (File.Exists(Path.Combine(dir, "pdanext-" + dateStr + "." + part + ".log")))
            {
                part++;
            }

            if (part == 1) return baseFile;
            string lastPart = Path.Combine(dir, "pdanext-" + dateStr + "." + (part - 1) + ".log");
            if (new FileInfo(lastPart).Length < MaxSizeBytes) return lastPart;

            return Path.Combine(dir, "pdanext-" + dateStr + "." + part + ".log");
        }

        static string RotateFile(string dir, string dateStr)
        {
            int part = 1;
            while (File.Exists(Path.Combine(dir, "pdanext-" + dateStr + "." + part + ".log")))
            {
                part++;
            }
            return Path.Combine(dir, "pdanext-" + dateStr + "." + part + ".log");
        }

        public static void CleanOldLogs(string dir)
        {
            try
            {
                var files = Directory.GetFiles(dir, "pdanext-*.log");
                DateTime cutoff = DateTime.UtcNow.Date.AddDays(-RetentionDays);

                foreach (var file in files)
                {
                    var info = new FileInfo(file);
                    // Extract date from pdanext-YYYYMMDD
                    string name = Path.GetFileNameWithoutExtension(info.Name);
                    // Handle .1, .2 extensions if present
                    if (name.Contains(".")) name = name.Substring(0, name.IndexOf('.'));
                    if (name.StartsWith("pdanext-") && name.Length >= 16)
                    {
                        string datePart = name.Substring(8, 8);
                        if (DateTime.TryParseExact(datePart, "yyyyMMdd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var fileDate))
                        {
                            if (fileDate < cutoff)
                            {
                                File.Delete(file);
                                continue;
                            }
                        }
                    }

                    if (info.LastWriteTimeUtc < cutoff)
                    {
                        File.Delete(file);
                    }
                }
            }
            catch
            {
            }
        }
    }
}
