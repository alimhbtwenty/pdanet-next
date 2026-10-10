using System.Globalization;

namespace PdaNetNext
{
    /// <summary>
    /// sing-box configuration for "whole PC" mode: a Wintun adapter captures all traffic,
    /// and everything leaves through the phone relay as SOCKS5 (TCP) and UDP-over-TCP (UDP).
    /// A-records are answered with fake IPs, so the phone, not the PC, resolves every name.
    /// tests/e2e/run-linux-e2e.sh runs this exact config against the relay.
    /// The adapter subnet and the fake-IP ranges are outside the LAN ranges that stay direct.
    /// </summary>
    public static class SingBoxConfig
    {
        public const string InterfaceName = "PdaNet-Next";

        public static string Build(int relayPort, bool keepLanDirect, string logLevel = "warn", string relayHost = "127.0.0.1")
        {
            string exclude = keepLanDirect
                ? @"
      ""route_exclude_address"": [
        ""10.0.0.0/8"", ""172.16.0.0/12"", ""192.168.0.0/16"", ""169.254.0.0/16"",
        ""fe80::/10""
      ],"
                : "";

            return @"{
  ""log"": { ""level"": """ + logLevel + @""", ""timestamp"": true },
  ""dns"": {
    ""servers"": [
      { ""type"": ""fakeip"", ""tag"": ""fakeip"", ""inet4_range"": ""198.18.0.0/15"", ""inet6_range"": ""fc00::/18"" },
      { ""type"": ""tcp"", ""tag"": ""remote"", ""server"": ""1.1.1.1"", ""detour"": ""relay"" }
    ],
    ""rules"": [
      { ""query_type"": [""A"", ""AAAA""], ""server"": ""fakeip"" }
    ],
    ""final"": ""remote"",
    ""strategy"": ""prefer_ipv4""
  },
  ""inbounds"": [
    {
      ""type"": ""tun"",
      ""tag"": ""tun-in"",
      ""interface_name"": """ + InterfaceName + @""",
      ""address"": [""100.64.231.1/30"", ""fdfe:dcba:9876::1/126""],
      ""mtu"": 9000," + exclude + @"
      ""auto_route"": true,
      ""strict_route"": true,
      ""stack"": ""mixed""
    }
  ],
  ""outbounds"": [
    {
      ""type"": ""socks"",
      ""tag"": ""relay"",
      ""server"": """ + relayHost + @""",
      ""server_port"": " + relayPort.ToString(CultureInfo.InvariantCulture) + @",
      ""version"": ""5"",
      ""udp_over_tcp"": { ""enabled"": true, ""version"": 2 }
    },
    { ""type"": ""direct"", ""tag"": ""direct"" }
  ],
  ""route"": {
    ""rules"": [
      { ""action"": ""sniff"" },
      { ""protocol"": ""dns"", ""action"": ""hijack-dns"" }
    ],
    ""final"": ""relay"",
    ""default_domain_resolver"": ""remote"",
    ""auto_detect_interface"": true
  }
}
";
        }
    }
}
