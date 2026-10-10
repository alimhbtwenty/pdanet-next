using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;

namespace PdaNext
{
    /// <summary>Snapshot from the phone relay's GET /status.</summary>
    public sealed class PhoneStatus
    {
        public string Version;
        public string Device;
        public string Uplink;
        public bool UplinkConnected;
        public bool VpnActive;
        public long BytesUp;
        public long BytesDown;
        public long ActiveTcp;
        public long ActiveUdp;

        public string UplinkDisplay
        {
            get
            {
                if (!UplinkConnected) return "No internet";
                switch (Uplink)
                {
                    case "mobile": return "Mobile data";
                    case "wifi": return "Wi-Fi";
                    case "ethernet": return "Ethernet";
                    default: return "Connected";
                }
            }
        }

        /// <summary>
        /// Raw socket on purpose: the system proxy may point at this very port, and
        /// WebRequest would try to use it.
        /// </summary>
        public static PhoneStatus Fetch(int port, int timeoutMs = 3000)
        {
            using (var client = new TcpClient())
            {
                var connect = client.BeginConnect("127.0.0.1", port, null, null);
                if (!connect.AsyncWaitHandle.WaitOne(timeoutMs)) throw new IOException("relay did not answer");
                client.EndConnect(connect);
                client.ReceiveTimeout = timeoutMs;
                client.SendTimeout = timeoutMs;
                var stream = client.GetStream();
                byte[] request = Encoding.ASCII.GetBytes("GET /status HTTP/1.1\r\nHost: phone\r\nConnection: close\r\n\r\n");
                stream.Write(request, 0, request.Length);
                var ms = new MemoryStream();
                stream.CopyTo(ms);
                string response = Encoding.UTF8.GetString(ms.ToArray());
                int body = response.IndexOf("\r\n\r\n", StringComparison.Ordinal);
                if (!response.StartsWith("HTTP/1.1 200") || body < 0) throw new IOException("unexpected relay answer");
                return Parse(response.Substring(body + 4));
            }
        }

        public static PhoneStatus Parse(string json)
        {
            var f = FlatJson.Parse(json);
            if (FlatJson.Str(f, "app") != "pdanext") throw new IOException("port is not a PdaNext relay");
            return new PhoneStatus
            {
                Version = FlatJson.Str(f, "version"),
                Device = FlatJson.Str(f, "device"),
                Uplink = FlatJson.Str(f, "uplink"),
                UplinkConnected = FlatJson.Str(f, "uplinkConnected") != "false",
                VpnActive = FlatJson.Str(f, "vpnActive") == "true",
                BytesUp = FlatJson.Long(f, "bytesUp"),
                BytesDown = FlatJson.Long(f, "bytesDown"),
                ActiveTcp = FlatJson.Long(f, "activeTcp"),
                ActiveUdp = FlatJson.Long(f, "activeUdp"),
            };
        }
    }

    /// <summary>Reader for the relay's flat JSON object (string, number, bool, null values only).</summary>
    public static class FlatJson
    {
        static readonly Regex Pair = new Regex(
            "\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|-?[0-9.eE+]+|true|false|null)",
            RegexOptions.Compiled);

        public static Dictionary<string, string> Parse(string json)
        {
            var map = new Dictionary<string, string>();
            foreach (Match m in Pair.Matches(json))
            {
                string value = m.Groups[3].Success ? Regex.Unescape(m.Groups[3].Value) : m.Groups[2].Value;
                map[Regex.Unescape(m.Groups[1].Value)] = value;
            }
            return map;
        }

        public static string Str(Dictionary<string, string> map, string key) =>
            map.TryGetValue(key, out var v) && v != "null" ? v : null;

        public static long Long(Dictionary<string, string> map, string key) =>
            long.TryParse(Str(map, key), NumberStyles.Integer, CultureInfo.InvariantCulture, out var v) ? v : 0;
    }
}
