using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace PdaNext
{
    public sealed class XrayServerProfile
    {
        public string Id { get; set; }
        public string Name { get; set; }
        public bool Selected { get; set; }
        public long? DelayMs { get; set; }

        public override string ToString() => $"XrayServerProfile({Name}, Selected={Selected})";
    }

    /// <summary>
    /// Pure functions for Xray request building and response parsing.
    /// Fully unit-testable without network dependencies.
    /// </summary>
    public static class XrayProtocol
    {
        public static string BuildSelectJson(string id)
        {
            return id == null ? "{\"id\":null}" : "{\"id\":\"" + EscapeJson(id) + "\"}";
        }

        public static string BuildDeleteJson(string id)
        {
            return "{\"id\":\"" + EscapeJson(id ?? "") + "\"}";
        }

        public static string BuildDelayJson(string id)
        {
            return "{\"id\":\"" + EscapeJson(id ?? "") + "\"}";
        }

        public static string BuildBypassJson(IEnumerable<string> domains)
        {
            if (domains == null) return "[]";
            var sb = new StringBuilder("[");
            bool first = true;
            foreach (var d in domains)
            {
                if (string.IsNullOrWhiteSpace(d)) continue;
                if (!first) sb.Append(",");
                first = false;
                sb.Append("\"").Append(EscapeJson(d.Trim())).Append("\"");
            }
            sb.Append("]");
            return sb.ToString();
        }

        public static string BuildHttpRequest(string method, string path, string body = null)
        {
            byte[] bodyBytes = body != null ? Encoding.UTF8.GetBytes(body) : new byte[0];
            var sb = new StringBuilder();
            sb.Append(method).Append(" ").Append(path).Append(" HTTP/1.1\r\n");
            sb.Append("Host: phone\r\n");
            sb.Append("Connection: close\r\n");
            if (method.Equals("POST", StringComparison.OrdinalIgnoreCase))
            {
                sb.Append("Content-Length: ").Append(bodyBytes.Length).Append("\r\n");
                sb.Append("Content-Type: application/json\r\n");
            }
            sb.Append("\r\n");
            if (bodyBytes.Length > 0)
            {
                sb.Append(body);
            }
            return sb.ToString();
        }

        public static bool TryParseHttpResponse(string raw, out int statusCode, out string body)
        {
            statusCode = 0;
            body = "";
            if (string.IsNullOrEmpty(raw)) return false;
            int statusEnd = raw.IndexOf("\r\n", StringComparison.Ordinal);
            if (statusEnd < 0) return false;
            string statusLine = raw.Substring(0, statusEnd);
            var parts = statusLine.Split(' ');
            if (parts.Length < 2 || !int.TryParse(parts[1], out statusCode)) return false;

            int bodyIdx = raw.IndexOf("\r\n\r\n", StringComparison.Ordinal);
            if (bodyIdx >= 0)
            {
                body = raw.Substring(bodyIdx + 4);
            }
            return true;
        }

        static readonly Regex ObjectRegex = new Regex(@"\{([^{}]*)\}", RegexOptions.Compiled);
        static readonly Regex StringArrayRegex = new Regex(@"\""((?:[^\""\\]|\\.)*)\""", RegexOptions.Compiled);

        public static List<XrayServerProfile> ParseProfilesJson(string json)
        {
            var list = new List<XrayServerProfile>();
            if (string.IsNullOrWhiteSpace(json)) return list;
            foreach (Match m in ObjectRegex.Matches(json))
            {
                var map = FlatJson.Parse("{" + m.Groups[1].Value + "}");
                string id = FlatJson.Str(map, "id");
                string name = FlatJson.Str(map, "name");
                bool selected = FlatJson.Str(map, "selected") == "true";
                if (!string.IsNullOrEmpty(id) && name != null)
                {
                    list.Add(new XrayServerProfile
                    {
                        Id = id,
                        Name = name,
                        Selected = selected,
                    });
                }
            }
            return list;
        }

        public static List<string> ParseStringArrayJson(string json)
        {
            var list = new List<string>();
            if (string.IsNullOrWhiteSpace(json)) return list;
            int start = json.IndexOf('[');
            int end = json.LastIndexOf(']');
            if (start < 0 || end <= start) return list;
            string inner = json.Substring(start + 1, end - start - 1);
            foreach (Match m in StringArrayRegex.Matches(inner))
            {
                list.Add(Regex.Unescape(m.Groups[1].Value));
            }
            return list;
        }

        public static long? ParseDelayResultJson(string json)
        {
            if (string.IsNullOrWhiteSpace(json)) return null;
            var map = FlatJson.Parse(json);
            if (!map.TryGetValue("ms", out var val) || val == "null") return null;
            if (long.TryParse(val, NumberStyles.Integer, CultureInfo.InvariantCulture, out var ms))
            {
                return ms;
            }
            return null;
        }

        public static string EscapeJson(string s)
        {
            if (s == null) return "";
            var sb = new StringBuilder();
            foreach (char c in s)
            {
                switch (c)
                {
                    case '"': sb.Append("\\\""); break;
                    case '\\': sb.Append("\\\\"); break;
                    case '\b': sb.Append("\\b"); break;
                    case '\f': sb.Append("\\f"); break;
                    case '\n': sb.Append("\\n"); break;
                    case '\r': sb.Append("\\r"); break;
                    case '\t': sb.Append("\\t"); break;
                    default:
                        if (c < ' ') sb.AppendFormat("\\u{0:x4}", (int)c);
                        else sb.Append(c);
                        break;
                }
            }
            return sb.ToString();
        }
    }
}
