using System;
using System.Globalization;
using System.IO;
using System.Net;
using System.Text;

namespace PdaNext
{
    public class PhoneLogPoller
    {
        private long currentSeq = 0L;
        private DateTime lastPollTime = DateTime.MinValue;
        private readonly object sync = new object();

        public long CurrentSeq => currentSeq;
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

        public void PollIfDue(int port, int intervalSeconds = 30)
        {
            if (DateTime.UtcNow - lastPollTime >= TimeSpan.FromSeconds(intervalSeconds))
            {
                Poll(port);
            }
        }

        public void Poll(int port)
        {
            lock (sync)
            {
                lastPollTime = DateTime.UtcNow;
                try
                {
                    PollLogs(port);
                    PollFailures(port);
                }
                catch
                {
                    // Logging should never crash
                }
            }
        }

        void PollLogs(int port)
        {
            string url = "http://127.0.0.1:" + port + "/log?since=" + currentSeq;
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.Proxy = null;
            req.Timeout = 5000;
            req.ReadWriteTimeout = 5000;

            string body = null;
            long? responseSeq = null;

            try
            {
                using (var resp = (HttpWebResponse)req.GetResponse())
                {
                    string seqHeader = resp.Headers["X-Log-Seq"];
                    if (!string.IsNullOrEmpty(seqHeader) && long.TryParse(seqHeader, NumberStyles.Integer, CultureInfo.InvariantCulture, out long seq))
                    {
                        responseSeq = seq;
                    }
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
                if (IsRestart(currentSeq, responseSeq.Value))
                {
                    // Phone restarted: sequence went backwards, start again from 0
                    currentSeq = 0L;
                    PollLogs(port);
                    return;
                }
                currentSeq = responseSeq.Value;
            }

            if (!string.IsNullOrWhiteSpace(body))
            {
                string redacted = SecretRedactor.Redact(body);
                SavePhoneLog(redacted);
            }
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
