using System;
using System.Collections.Generic;
using System.Text.RegularExpressions;

namespace PdaNext
{
    public sealed class VpnApp
    {
        public string Package { get; set; }
        public string Label { get; set; }
        public bool IsActive { get; set; }

        public string DisplayName => string.IsNullOrEmpty(Label) ? Package : Label;

        public override string ToString() => $"{DisplayName} ({Package}){(IsActive ? " [Active]" : "")}";
    }

    /// <summary>
    /// Pure parsing and discovery routines for Android VPN apps over adb.
    /// Supports querying via cmd package, pm query-services, dumpsys package,
    /// and active VPN detection via dumpsys connectivity.
    /// </summary>
    public static class VpnDiscovery
    {
        /// <summary>
        /// Known VPN app package names mapped to their standard user-facing brand labels.
        /// Android returns resource IDs (labelRes) when an app uses @string/app_name
        /// and leaves nonLocalizedLabel null. This table maps well-known VPN clients.
        /// </summary>
        public static readonly Dictionary<string, string> KnownLabels = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            { "com.v2ray.ang", "v2rayNG" },
            { "com.github.kr328.clash", "Clash Meta" },
            { "com.github.clash_for_android", "Clash for Android" },
            { "app.hiddify.com", "Hiddify" },
            { "io.nekohasekai.sagernet", "SagerNet" },
            { "io.nekohasekai.sfa", "sing-box" },
            { "com.wireguard.android", "WireGuard" },
            { "de.blinkt.openvpn", "OpenVPN for Android" },
            { "net.openvpn.openvpn", "OpenVPN Connect" },
            { "org.outline.android.client", "Outline" },
            { "com.cloudflare.onedotonedotonedotone", "1.1.1.1 + WARP" },
            { "com.psiphon3.subscription", "Psiphon" },
            { "com.psiphon3", "Psiphon" },
            { "org.torproject.android", "Orbot" },
            { "org.torproject.torbrowser", "Tor Browser" },
            { "com.adguard.android", "AdGuard" },
            { "ch.protonvpn.android", "Proton VPN" },
            { "com.nordvpn.android", "NordVPN" },
            { "com.expressvpn.vpn", "ExpressVPN" },
            { "com.surfshark.vpnclient.android", "Surfshark" },
            { "com.mullvad.vpn", "Mullvad VPN" },
            { "com.windscribe.vpn", "Windscribe" },
            { "com.tailscale.ipn", "Tailscale" },
            { "com.getlantern.lantern", "Lantern" },
            { "com.github.shadowsocks", "Shadowsocks" },
            { "co.mona.android", "V2Fly" },
            { "io.v2ray.v2fly", "V2Fly" },
            { "org.strongswan.android", "strongSwan" },
            { "com.cisco.anyconnect.vpn.android.avf", "Cisco AnyConnect" },
            { "com.pdanet", "PdaNet+" },
            { "io.github.alimhbtwenty.pdanext", "PdaNext" }
        };

        /// <summary>
        /// Derives a clean, readable label for an app package if no localized label is present.
        /// </summary>
        public static string GetAppLabel(string packageName, string nonLocalizedLabel = null)
        {
            if (!string.IsNullOrWhiteSpace(nonLocalizedLabel) && nonLocalizedLabel != "null")
                return nonLocalizedLabel.Trim();

            if (string.IsNullOrWhiteSpace(packageName))
                return "Unknown VPN";

            string trimmedPkg = packageName.Trim();
            if (KnownLabels.TryGetValue(trimmedPkg, out string known))
                return known;

            // Generate clean label from package name:
            // e.g. "com.example.fast_vpn" -> "Fast Vpn"
            string[] parts = trimmedPkg.Split('.');
            string candidate = parts[parts.Length - 1];
            if (candidate.Length <= 3 && parts.Length >= 2)
                candidate = parts[parts.Length - 2] + " " + candidate;

            candidate = candidate.Replace('_', ' ').Replace('-', ' ');
            candidate = Regex.Replace(candidate, "([a-z])([A-Z])", "$1 $2");
            var words = candidate.Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
            for (int i = 0; i < words.Length; i++)
            {
                if (words[i].Length > 0)
                    words[i] = char.ToUpper(words[i][0]) + (words[i].Length > 1 ? words[i].Substring(1) : "");
            }
            return string.Join(" ", words);
        }

        /// <summary>
        /// Parses the output of `adb shell cmd package query-services -a android.net.VpnService`
        /// or `adb shell pm query-services -a android.net.VpnService`.
        /// </summary>
        public static List<VpnApp> ParseQueryServices(string output)
        {
            var apps = new Dictionary<string, VpnApp>(StringComparer.OrdinalIgnoreCase);
            if (string.IsNullOrWhiteSpace(output)) return new List<VpnApp>();

            string currentPackage = null;
            string currentLabel = null;

            void FlushCurrent()
            {
                if (!string.IsNullOrEmpty(currentPackage) && !apps.ContainsKey(currentPackage))
                {
                    apps[currentPackage] = new VpnApp
                    {
                        Package = currentPackage,
                        Label = GetAppLabel(currentPackage, currentLabel)
                    };
                }
                currentPackage = null;
                currentLabel = null;
            }

            foreach (var rawLine in output.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries))
            {
                string line = rawLine.Trim();

                if (line.StartsWith("Service #") || line.StartsWith("priority="))
                {
                    FlushCurrent();
                }

                // packageName=com.v2ray.ang
                int pkgIdx = line.IndexOf("packageName=", StringComparison.OrdinalIgnoreCase);
                if (pkgIdx >= 0)
                {
                    string rest = line.Substring(pkgIdx + 12);
                    string pkg = rest.Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries)[0];
                    if (!string.IsNullOrEmpty(pkg)) currentPackage = pkg;
                }

                // name=com.v2ray.ang/.service.V2RayVpnService
                if (currentPackage == null && line.StartsWith("name=", StringComparison.OrdinalIgnoreCase))
                {
                    string rest = line.Substring(5).Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries)[0];
                    int slash = rest.IndexOf('/');
                    if (slash > 0) currentPackage = rest.Substring(0, slash);
                }

                // nonLocalizedLabel=Clash Meta or nonLocalizedLabel=null
                int lblIdx = line.IndexOf("nonLocalizedLabel=", StringComparison.OrdinalIgnoreCase);
                if (lblIdx >= 0)
                {
                    string rest = line.Substring(lblIdx + 18);
                    // In Android dumpsys, this field is followed by " icon=" or " banner=" or end of line.
                    var match = Regex.Match(rest, @"^(.*?)(?=\s+(?:icon|banner)=|\r|\n|$)", RegexOptions.Singleline);
                    string labelVal = match.Success ? match.Groups[1].Value.Trim() : rest.Trim();
                    if (labelVal != "null" && !string.IsNullOrWhiteSpace(labelVal))
                        currentLabel = labelVal;
                }
            }

            FlushCurrent();
            return new List<VpnApp>(apps.Values);
        }

        /// <summary>
        /// Fallback parser for `adb shell dumpsys package` under `android.net.VpnService:`.
        /// Handles output from the Service Resolver Table:
        ///   Service Resolver Table:
        ///     Non-Data Actions:
        ///       android.net.VpnService:
        ///         41e6e9b8 com.v2ray.ang/.service.V2RayVpnService filter 41e6ebb8
        /// </summary>
        public static List<VpnApp> ParseDumpsysPackageServices(string output)
        {
            var apps = new Dictionary<string, VpnApp>(StringComparer.OrdinalIgnoreCase);
            if (string.IsNullOrWhiteSpace(output)) return new List<VpnApp>();

            bool inVpnSection = false;
            foreach (var rawLine in output.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries))
            {
                string line = rawLine.Trim();

                if (line.Contains("android.net.VpnService:"))
                {
                    inVpnSection = true;
                    continue;
                }

                if (inVpnSection)
                {
                    // Section ends when an unindented header or another non-service line appears
                    if (line.EndsWith(":") && !line.Contains("android.net.VpnService"))
                    {
                        inVpnSection = false;
                        continue;
                    }

                    // Match component format: "<hex> <packageName>/<serviceClass>"
                    var match = Regex.Match(line, @"([a-zA-Z0-9_\.]+)/[a-zA-Z0-9_\.\$]+");
                    if (match.Success)
                    {
                        string pkg = match.Groups[1].Value;
                        if (!apps.ContainsKey(pkg))
                        {
                            apps[pkg] = new VpnApp
                            {
                                Package = pkg,
                                Label = GetAppLabel(pkg)
                            };
                        }
                    }
                }
            }

            return new List<VpnApp>(apps.Values);
        }

        /// <summary>
        /// Documenting how the active VPN owner is found in `dumpsys connectivity`:
        /// 
        /// 1. In Android's ConnectivityService dump, active networks appear under `Current Networks:`
        ///    as `NetworkAgentInfo` records.
        /// 2. For an active VPN network, the NetworkInfo has transport `type: VPN` (or `VPN CONNECTED` or
        ///    `Transports: ...VPN...`) and state `CONNECTED` (or `CONNECTED/CONNECTED`).
        /// 3. In Android's Vpn.java implementation, when the VPN network agent is created and registered,
        ///    Android sets `mNetworkInfo.setExtraInfo(mPackage)` (or `VPN:<package>`). Therefore,
        ///    `extra: "<package>"` or `extra: VPN:<package>` in the VPN's NetworkAgentInfo directly specifies
        ///    the owner package name.
        /// 4. Furthermore, ConnectivityService dumps a dedicated `VPNs:` section detailing each active user's
        ///    VPN (e.g. `User 0:`) with `Package: <package>` or indented `<package>` with `Interface: tun...`
        ///    and `Legacy: false`.
        /// 5. This function parses both representations to reliably find the active VPN app's package name.
        /// </summary>
        public static string ParseActiveVpnPackage(string dumpsysConnectivityOutput)
        {
            if (string.IsNullOrWhiteSpace(dumpsysConnectivityOutput)) return null;

            var lines = dumpsysConnectivityOutput.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);

            // Strategy 1: Check NetworkAgentInfo blocks for an active VPN agent
            for (int i = 0; i < lines.Length; i++)
            {
                string line = lines[i];
                bool isNetworkInfo = line.Contains("NetworkAgentInfo") || line.Contains("NetworkInfo:");
                bool isVpn = line.IndexOf("type: VPN", StringComparison.OrdinalIgnoreCase) >= 0 ||
                             line.IndexOf("VPN CONNECTED", StringComparison.OrdinalIgnoreCase) >= 0 ||
                             line.IndexOf("Transports: VPN", StringComparison.OrdinalIgnoreCase) >= 0 ||
                             line.IndexOf("Transports: CELLULAR|VPN", StringComparison.OrdinalIgnoreCase) >= 0 ||
                             line.IndexOf("Transports: WIFI|VPN", StringComparison.OrdinalIgnoreCase) >= 0;
                bool isConnected = line.IndexOf("CONNECTED", StringComparison.OrdinalIgnoreCase) >= 0;

                if (isNetworkInfo && isVpn && isConnected)
                {
                    var extraMatch = Regex.Match(line, @"extra:\s*""?([^"",\s\}]+)""?", RegexOptions.IgnoreCase);
                    if (extraMatch.Success)
                    {
                        string val = extraMatch.Groups[1].Value.Trim();
                        if (val.StartsWith("VPN:", StringComparison.OrdinalIgnoreCase))
                            val = val.Substring(4).Trim();

                        if (!string.IsNullOrEmpty(val) && val != "(null)" && val != "null" && val.Contains("."))
                            return val;
                    }
                }
            }

            // Strategy 2: Check VPNs: section
            bool inVpns = false;
            for (int i = 0; i < lines.Length; i++)
            {
                string line = lines[i];
                if (line.Trim().Equals("VPNs:", StringComparison.OrdinalIgnoreCase))
                {
                    inVpns = true;
                    continue;
                }

                if (inVpns)
                {
                    if (!line.StartsWith(" ") && !line.StartsWith("\t") && line.EndsWith(":"))
                    {
                        inVpns = false;
                        continue;
                    }

                    string trimmed = line.Trim();

                    // "Package: com.foo.bar"
                    if (trimmed.StartsWith("Package:", StringComparison.OrdinalIgnoreCase))
                    {
                        string pkg = trimmed.Substring(8).Trim();
                        if (!string.IsNullOrEmpty(pkg) && pkg.Contains("."))
                            return pkg;
                    }

                    // Indented package line followed by "Legacy: false" or "Interface: tun0"
                    if (trimmed.Contains(".") && !trimmed.Contains(":") && !trimmed.Contains(" ") && i + 1 < lines.Length)
                    {
                        string nextLine = lines[i + 1].Trim();
                        if (nextLine.StartsWith("Legacy:", StringComparison.OrdinalIgnoreCase) ||
                            nextLine.StartsWith("Interface:", StringComparison.OrdinalIgnoreCase) ||
                            nextLine.StartsWith("Addresses:", StringComparison.OrdinalIgnoreCase))
                        {
                            return trimmed;
                        }
                    }
                }
            }

            return null;
        }

        /// <summary>
        /// Combines discovered apps and marks the active VPN. If the active VPN package was not in the
        /// query list, it adds an entry for it. Returns the sorted list with active VPN first.
        /// </summary>
        public static List<VpnApp> MergeAndMarkActive(IEnumerable<VpnApp> apps, string activePackage)
        {
            var result = new List<VpnApp>();
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

            if (apps != null)
            {
                foreach (var app in apps)
                {
                    if (app == null || string.IsNullOrEmpty(app.Package)) continue;
                    if (seen.Add(app.Package))
                    {
                        bool isActive = !string.IsNullOrEmpty(activePackage) &&
                                        string.Equals(app.Package, activePackage, StringComparison.OrdinalIgnoreCase);
                        result.Add(new VpnApp
                        {
                            Package = app.Package,
                            Label = app.Label,
                            IsActive = isActive
                        });
                    }
                }
            }

            if (!string.IsNullOrEmpty(activePackage) && !seen.Contains(activePackage))
            {
                result.Insert(0, new VpnApp
                {
                    Package = activePackage,
                    Label = GetAppLabel(activePackage),
                    IsActive = true
                });
            }

            // Sort: active VPN first, then alphabetically by display name
            result.Sort((a, b) =>
            {
                if (a.IsActive != b.IsActive) return a.IsActive ? -1 : 1;
                return string.Compare(a.DisplayName, b.DisplayName, StringComparison.OrdinalIgnoreCase);
            });

            return result;
        }
    }
}
