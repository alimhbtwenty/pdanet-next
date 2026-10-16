using System;
using System.Globalization;
using System.IO;
using System.Net;
using System.Text;

namespace PdaNext
{
    /// <summary>
    /// Copies the phone's log to the PC: new lines of GET /log go to phone-YYYYMMDD.log, and the
    /// phone's failure file (GET /log/failures) to phone-failures.log. The phone's boot id and the
    /// last sequence are kept in a state file next to the logs, so reconnecting or restarting the PC
    /// app never saves a line twice; a new boot id (the phone app restarted) starts again from 0.
    /// </summary>
    public class PhoneLogPoller
    {
        private long currentSeq = 0L;
        private string bootId;
        private bool stateLoaded;
        private DateTime lastPollTime = DateTime.MinValue;
        private readonly object sync = new object();
        private readonly string stateFile;

        public PhoneLogPoller(string stateFile = null)
        {
            this.stateFile = stateFile ?? Path.Combine(FileLogger.LogDirectory, "phone-log-state.txt");
        }

        public long CurrentSeq => currentSeq;
        public string BootId => bootId;
        public DateTime LastPollTime => lastPollTime;

        public void Reset()
        {
            lock (sync)
            {
                currentSeq = 0L;
                lastPollTime = DateTime.MinValue;
            }
        }

        /// <summary>
        /// Pure function for sequence progression and restart detection.
        /// If responseSeq &lt; currentSeq, the phone restarted: returns 0L.
        /// Otherwise returns responseSeq.
        /// </summary>
        public static long ComputeNextSequence(long currentSeq, long responseSeq)
        {
            if (responseSeq < currentSeq)
            {
                return 0L;
            }
            return responseSeq;
        }

        public static bool IsRestart(long currentSeq, long responseSeq)
        {
            return responseSeq < currentSeq;
        }

        /// <summary>
        /// True when the answer belongs to another phone boot than the stored one (or the sequence
        /// went backwards): the body is not what was asked for, fetch again from 0.
        /// </summary>
        public static bool NeedsRestart(string storedBoot, long storedSeq, string responseBoot, long responseSeq)
        {
            if (!string.IsNullOrEmpty(responseBoot) && responseBoot != storedBoot) return true;
            return IsRestart(storedSeq, responseSeq);
        }

        public void PollIfDue(int port, int intervalSeconds = 30)
        {
            if (DateTime.UtcNow - lastPollTime >= TimeSpan.FromSeconds(intervalSeconds))
            {
                Poll(port);
            }
        }

        /// <summary>
        /// Saves new phone log lines. The failure file only grows (it is never deleted), so it is
        /// copied only when asked (on connect and disconnect), not on every periodic poll.
        /// </summary>
        public void Poll(int port, bool includeFailures = false)
        {
            lock (sync)
            {
                lastPollTime = DateTime.UtcNow;
                try
                {
                    LoadState();
                    PollLogs(port, allowRestart: true);
                    if (includeFailures) PollFailures(port);
                }
                catch
                {
                    // Logging should never crash
                }
            }
        }

        void LoadState()
        {
            if (stateLoaded) return;
            stateLoaded = true;
            try
            {
                if (!File.Exists(stateFile)) return;
                var parts = File.ReadAllText(stateFile).Trim().Split(' ');
                if (parts.Length == 2 && long.TryParse(parts[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out long seq))
                {
                    bootId = parts[0];
                    currentSeq = seq;
                }
            }
            catch
            {
            }
        }

        void SaveState()
        {
            try
            {
                string dir = Path.GetDirectoryName(stateFile);
                if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir)) Directory.CreateDirectory(dir);
                File.WriteAllText(stateFile, (bootId ?? "-") + " " + currentSeq.ToString(CultureInfo.InvariantCulture));
            }
            catch
            {
            }
        }

        void PollLogs(int port, bool allowRestart)
        {
            string url = "http://127.0.0.1:" + port + "/log?since=" + currentSeq;
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.Proxy = null;
            req.Timeout = 5000;
            req.ReadWriteTimeout = 5000;

            string body = null;
            long? responseSeq = null;
            string responseBoot = null;

            try
            {
                using (var resp = (HttpWebResponse)req.GetResponse())
                {
                    string seqHeader = resp.Headers["X-Log-Seq"];
                    if (!string.IsNullOrEmpty(seqHeader) && long.TryParse(seqHeader, NumberStyles.Integer, CultureInfo.InvariantCulture, out long seq))
                    {
                        responseSeq = seq;
                    }
                    responseBoot = resp.Headers["X-Log-Boot"];
                    using (var stream = resp.GetResponseStream())
                    using (var reader = new StreamReader(stream, Encoding.UTF8))
                    {
                        body = reader.ReadToEnd();
                    }
                }
            }
            catch
            {
                return;
            }

            if (responseSeq.HasValue)
            {
                if (allowRestart && NeedsRestart(bootId, currentSeq, responseBoot, responseSeq.Value))
                {
                    // Another phone boot: what we asked for does not exist there, start again from 0.
                    if (!string.IsNullOrEmpty(responseBoot)) bootId = responseBoot;
                    currentSeq = 0L;
                    PollLogs(port, allowRestart: false);
                    return;
                }
                currentSeq = responseSeq.Value;
            }

            if (!string.IsNullOrWhiteSpace(body))
            {
                string redacted = SecretRedactor.Redact(body);
                SavePhoneLog(redacted);
            }
            SaveState();
        }

        void SavePhoneLog(string text)
        {
            try
            {
                string dir = FileLogger.LogDirectory;
                if (!Directory.Exists(dir)) Directory.CreateDirectory(dir);

                string today = DateTime.UtcNow.ToString("yyyyMMdd", CultureInfo.InvariantCulture);
                string path = Path.Combine(dir, "phone-" + today + ".log");

                using (var writer = new StreamWriter(path, true, Encoding.UTF8))
                {
                    writer.Write(text);
                    if (!text.EndsWith("\n") && !text.EndsWith("\r\n"))
                    {
                        writer.WriteLine();
                    }
                }
            }
            catch
            {
            }
        }

        void PollFailures(int port)
        {
            string url = "http://127.0.0.1:" + port + "/log/failures";
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.Proxy = null;
            req.Timeout = 5000;
            req.ReadWriteTimeout = 5000;

            string body = null;
            try
            {
                using (var resp = (HttpWebResponse)req.GetResponse())
                using (var stream = resp.GetResponseStream())
                using (var reader = new StreamReader(stream, Encoding.UTF8))
                {
                    body = reader.ReadToEnd();
                }
            }
            catch
            {
                return;
            }

            if (!string.IsNullOrEmpty(body))
            {
                try
                {
                    string dir = FileLogger.LogDirectory;
                    if (!Directory.Exists(dir)) Directory.CreateDirectory(dir);

                    string path = Path.Combine(dir, "phone-failures.log");
                    string redacted = SecretRedactor.Redact(body);
                    File.WriteAllText(path, redacted, Encoding.UTF8);
                }
                catch
                {
                }
            }
        }
    }
}
