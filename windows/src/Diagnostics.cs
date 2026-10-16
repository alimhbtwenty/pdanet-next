using System;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Text;

namespace PdaNext
{
    public static class Diagnostics
    {
        public static string Collect(string zipPath, int port, string cachedStatusJson = null)
        {
            // 1. PC log
            string pcLog = GetPcLogs();

            // 2. Phone log (fetched from GET /log through the adb forward)
            string phoneLog = FetchHttp("http://127.0.0.1:" + port + "/log", 4000) ??
                              "Phone log unavailable (Relay not reachable on 127.0.0.1:" + port + ")";

            // 3. Last /status JSON
            string statusJson = FetchHttp("http://127.0.0.1:" + port + "/status", 3000) ??
                                (cachedStatusJson ?? "{}");

            // 4. Command outputs
            string ipconfig = ProcessRunner.Run("ipconfig.exe", "/all", 8000).Output;
            string route = ProcessRunner.Run("route.exe", "print -4", 8000).Output;
            string interfaces = ProcessRunner.Run("netsh.exe", "interface ipv4 show interfaces", 8000).Output;
            string nrpt = ProcessRunner.Run("powershell.exe",
                "-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"Get-DnsClientNrptRule | Format-List\"",
                12000).Output;

            // Ensure secrets are redacted from all diagnostic outputs
            pcLog = SecretRedactor.Redact(pcLog);
            phoneLog = SecretRedactor.Redact(phoneLog);
            statusJson = SecretRedactor.Redact(statusJson);
            ipconfig = SecretRedactor.Redact(ipconfig);
            route = SecretRedactor.Redact(route);
            interfaces = SecretRedactor.Redact(interfaces);
            nrpt = SecretRedactor.Redact(nrpt);

            if (File.Exists(zipPath)) File.Delete(zipPath);

            using (var zip = ZipFile.Open(zipPath, ZipArchiveMode.Create))
            {
                AddEntry(zip, "pc.log", pcLog);
                AddEntry(zip, "phone.log", phoneLog);
                AddEntry(zip, "status.json", statusJson);
                AddEntry(zip, "ipconfig.txt", ipconfig);
                AddEntry(zip, "route.txt", route);
                AddEntry(zip, "interfaces.txt", interfaces);
                AddEntry(zip, "nrpt.txt", nrpt);
            }

            return zipPath;
        }

        static void AddEntry(ZipArchive zip, string entryName, string content)
        {
            var entry = zip.CreateEntry(entryName, CompressionLevel.Optimal);
            using (var writer = new StreamWriter(entry.Open(), Encoding.UTF8))
            {
                writer.Write(content);
            }
        }

        static string GetPcLogs()
        {
            try
            {
                string dir = FileLogger.LogDirectory;
                if (!Directory.Exists(dir)) return "No logs directory found at " + dir;

                var files = Directory.GetFiles(dir, "pdanext-*.log");
                if (files.Length == 0) return "No PC log files found.";

                Array.Sort(files);
                var sb = new StringBuilder();
                foreach (var file in files)
                {
                    sb.AppendLine("=== " + Path.GetFileName(file) + " ===");
                    try
                    {
                        using (var stream = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                        using (var reader = new StreamReader(stream, Encoding.UTF8))
                        {
                            sb.AppendLine(reader.ReadToEnd());
                        }
                    }
                    catch (Exception e)
                    {
                        sb.AppendLine("Error reading log file: " + e.Message);
                    }
                }
                return sb.ToString();
            }
            catch (Exception ex)
            {
                return "Failed to read PC logs: " + ex.Message;
            }
        }

        static string FetchHttp(string url, int timeoutMs)
        {
            try
            {
                var req = (HttpWebRequest)WebRequest.Create(url);
                req.Proxy = null; // the system proxy may point at this very port (see PhoneStatus.Fetch)
                req.Timeout = timeoutMs;
                req.ReadWriteTimeout = timeoutMs;
                using (var resp = req.GetResponse())
                using (var stream = resp.GetResponseStream())
                using (var reader = new StreamReader(stream, Encoding.UTF8))
                {
                    return reader.ReadToEnd();
                }
            }
            catch
            {
                return null;
            }
        }
    }
}
