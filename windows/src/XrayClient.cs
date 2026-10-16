using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Sockets;
using System.Text;

namespace PdaNext
{
    /// <summary>
    /// Communicates with the phone's Xray control endpoints through the adb-forwarded port.
    /// Raw socket connection on 127.0.0.1 (no system proxy).
    /// </summary>
    public static class XrayClient
    {
        public static string SendRequest(int port, string method, string path, string body = null, int timeoutMs = 4000)
        {
            using (var client = new TcpClient())
            {
                var connect = client.BeginConnect("127.0.0.1", port, null, null);
                if (!connect.AsyncWaitHandle.WaitOne(timeoutMs))
                {
                    throw new IOException("Phone relay did not answer");
                }
                client.EndConnect(connect);
                client.ReceiveTimeout = timeoutMs;
                client.SendTimeout = timeoutMs;

                string rawReq = XrayProtocol.BuildHttpRequest(method, path, body);
                byte[] reqBytes = Encoding.UTF8.GetBytes(rawReq);
                var stream = client.GetStream();
                stream.Write(reqBytes, 0, reqBytes.Length);
                stream.Flush();

                var ms = new MemoryStream();
                stream.CopyTo(ms);
                string rawResp = Encoding.UTF8.GetString(ms.ToArray());

                if (!XrayProtocol.TryParseHttpResponse(rawResp, out int statusCode, out string respBody))
                {
                    throw new IOException("Unexpected response from phone relay");
                }

                if (statusCode != 200)
                {
                    throw new IOException($"Relay returned HTTP {statusCode}: {respBody}");
                }

                return respBody;
            }
        }

        public static List<XrayServerProfile> GetProfiles(int port, int timeoutMs = 3000)
        {
            string body = SendRequest(port, "GET", "/xray/profiles", null, timeoutMs);
            return XrayProtocol.ParseProfilesJson(body);
        }

        public static List<string> ImportLinks(int port, string linksText, int timeoutMs = 5000)
        {
            string body = SendRequest(port, "POST", "/xray/import", linksText, timeoutMs);
            return XrayProtocol.ParseStringArrayJson(body);
        }

        public static void Select(int port, string id, int timeoutMs = 3000)
        {
            string reqJson = XrayProtocol.BuildSelectJson(id);
            SendRequest(port, "POST", "/xray/select", reqJson, timeoutMs);
        }

        public static void Delete(int port, string id, int timeoutMs = 3000)
        {
            string reqJson = XrayProtocol.BuildDeleteJson(id);
            SendRequest(port, "POST", "/xray/delete", reqJson, timeoutMs);
        }

        public static long? MeasureDelay(int port, string id, int timeoutMs = 8000)
        {
            string reqJson = XrayProtocol.BuildDelayJson(id);
            string body = SendRequest(port, "POST", "/xray/delay", reqJson, timeoutMs);
            return XrayProtocol.ParseDelayResultJson(body);
        }

        public static List<string> GetBypassDomains(int port, int timeoutMs = 3000)
        {
            string body = SendRequest(port, "GET", "/xray/bypass", null, timeoutMs);
            return XrayProtocol.ParseStringArrayJson(body);
        }

        public static List<string> SetBypassDomains(int port, IEnumerable<string> domains, int timeoutMs = 3000)
        {
            string reqJson = XrayProtocol.BuildBypassJson(domains);
            string body = SendRequest(port, "POST", "/xray/bypass", reqJson, timeoutMs);
            return XrayProtocol.ParseStringArrayJson(body);
        }
    }
}
