using System;
using System.Text.RegularExpressions;

namespace PdaNext
{
    public static class SecretRedactor
    {
        static readonly Regex VpnUrlRegex = new Regex(@"(?i)\b(vless|vmess|trojan|ss)://[^\s'""]+", RegexOptions.Compiled);
        static readonly Regex UuidRegex = new Regex(@"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b", RegexOptions.Compiled);
        static readonly Regex CredentialsInUrlRegex = new Regex(@"(?i)\b([a-zA-Z][a-zA-Z0-9+.-]*://)([^:@\s]+):([^@\s]+)@", RegexOptions.Compiled);
        static readonly Regex KeyValueSecretRegex = new Regex(@"(?i)\b(password|secret|key|token|auth|bearer)\s*[:=]\s*([^\s,;]+)", RegexOptions.Compiled);

        public static string Redact(string input)
        {
            if (string.IsNullOrEmpty(input)) return input;
            string s = VpnUrlRegex.Replace(input, "$1://[REDACTED]");
            s = UuidRegex.Replace(s, "[REDACTED-UUID]");
            s = CredentialsInUrlRegex.Replace(s, "$1[REDACTED]:[REDACTED]@");
            s = KeyValueSecretRegex.Replace(s, "$1=[REDACTED]");
            return s;
        }
    }
}
