using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using PdaNext;

// Tests for the parts of the Windows app that do not need Windows.
//   dotnet run --project windows/tests
// Optional, with a running relay (gradle -p core installDist && core/build/install/core/bin/core):
//   RELAY_PORT=28080   fetches /status and does a router-mode handshake + ping over the real link code

int failed = 0, passed = 0;

void Test(string name, Action body)
{
    try { body(); passed++; Console.WriteLine("PASS  " + name); }
    catch (Exception e) { failed++; Console.WriteLine("FAIL  " + name + "\n      " + e.Message); }
}

void Equal<T>(T expected, T actual, string what = "")
{
    if (!EqualityComparer<T>.Default.Equals(expected, actual))
        throw new Exception($"{what} expected <{expected}> but was <{actual}>");
}

Test("adb devices -l: ready, unauthorized, emulator skipped", () =>
{
    var list = Adb.ParseDevices(
        "* daemon not running; starting now at tcp:5037\n" +
        "* daemon started successfully\n" +
        "List of devices attached\r\n" +
        "R58N12ABCDE            device usb:1-1 product:a52qnsxx model:SM_A525F device:a52q transport_id:3\r\n" +
        "0123456789ABCDEF       unauthorized usb:1-2 transport_id:4\r\n" +
        "emulator-5554          device product:sdk_gphone64 model:sdk_gphone64_x86_64 transport_id:1\r\n" +
        "\r\n");
    Equal(2, list.Count, "count");
    Equal("R58N12ABCDE", list[0].Serial);
    Equal(true, list[0].Ready);
    Equal("SM A525F", list[0].DisplayName);
    Equal("unauthorized", list[1].State);
    Equal(false, list[1].Ready);
    Equal("0123456789ABCDEF", list[1].DisplayName);
});

Test("adb devices -l: 'no permissions' state stays readable", () =>
{
    var list = Adb.ParseDevices("List of devices attached\nABC    no permissions (user in plugdev group); see [http://developer.android.com/tools/device.html]\n");
    Equal("no permissions", list[0].State);
});

Test("adb devices -l: empty list", () => Equal(0, Adb.ParseDevices("List of devices attached\n\n").Count));

Test("phone status json from the relay", () =>
{
    var s = PhoneStatus.Parse(
        "{\"app\":\"pdanext\",\"version\":\"3.0.0\",\"bytesUp\":5004433,\"bytesDown\":55029029,\"activeTcp\":3," +
        "\"activeUdp\":2,\"totalConnections\":58,\"failedConnections\":0,\"uplinkConnected\":true,\"uplink\":\"mobile\"," +
        "\"vpnActive\":true,\"device\":\"samsung SM-A525F \\\"test\\\"\"}");
    Equal(55029029L, s.BytesDown);
    Equal(5004433L, s.BytesUp);
    Equal(true, s.VpnActive);
    Equal("Mobile data", s.UplinkDisplay);
    Equal("samsung SM-A525F \"test\"", s.Device);
});

Test("phone status rejects a port that is not the relay", () =>
{
    try { PhoneStatus.Parse("{\"hello\":1}"); throw new Exception("no exception"); }
    catch (IOException) { }
});

Test("secret redactor: strips vless link and uuid from log line", () =>
{
    string uuid = "12345678-abcd-ef01-2345-6789abcdef01";
    string vless = "vless://" + uuid + "@vpn.example.com:443?security=reality&sni=test.com#my-vpn";
    string line = "Tunnel failed to connect to " + vless + " with user id " + uuid;
    string redacted = SecretRedactor.Redact(line);

    if (redacted.Contains(vless)) throw new Exception("vless URL was not stripped");
    if (redacted.Contains(uuid)) throw new Exception("UUID was not stripped");
    if (!redacted.Contains("vless://[REDACTED]")) throw new Exception("Expected vless redaction placeholder");
    if (!redacted.Contains("[REDACTED-UUID]")) throw new Exception("Expected UUID redaction placeholder");
});

Test("secret redactor: credentials in url and passwords stripped", () =>
{
    string line = "Fetching http://admin:superSecret123@192.168.42.1:28080/config?password=pass123";
    string redacted = SecretRedactor.Redact(line);
    if (redacted.Contains("superSecret123")) throw new Exception("Password in URL was not stripped");
    if (redacted.Contains("pass123")) throw new Exception("Password param was not stripped");
});

Test("file logger: no deletion by FileLogger", () =>
{
    string tempDir = Path.Combine(Path.GetTempPath(), "pdanext-test-logs-" + Guid.NewGuid().ToString("N"));
    try
    {
        Directory.CreateDirectory(tempDir);
        string oldFile = Path.Combine(tempDir, "pdanext-20200101.log");
        File.WriteAllText(oldFile, "old logs that must never be deleted");
        File.SetLastWriteTimeUtc(oldFile, DateTime.UtcNow.AddDays(-30));

        // FileLogger never deletes old files
        if (!File.Exists(oldFile)) throw new Exception("Old log file should never be deleted");
    }
    finally
    {
        if (Directory.Exists(tempDir)) Directory.Delete(tempDir, true);
    }
});

Test("phone log poller: since and restart logic", () =>
{
    // Normal progression: responseSeq >= currentSeq
    Equal(10L, PhoneLogPoller.ComputeNextSequence(0L, 10L), "normal progression from 0");
    Equal(false, PhoneLogPoller.IsRestart(0L, 10L), "not restart");

    Equal(25L, PhoneLogPoller.ComputeNextSequence(10L, 25L), "normal progression");
    Equal(false, PhoneLogPoller.IsRestart(10L, 25L), "not restart");

    Equal(25L, PhoneLogPoller.ComputeNextSequence(25L, 25L), "same seq when no new logs");
    Equal(false, PhoneLogPoller.IsRestart(25L, 25L), "not restart");

    // Phone restarted: sequence went backwards (responseSeq < currentSeq)
    Equal(0L, PhoneLogPoller.ComputeNextSequence(100L, 5L), "sequence went backwards resets to 0");
    Equal(true, PhoneLogPoller.IsRestart(100L, 5L), "restart detected");
});

Test("phone log poller: a reconnect continues, a new phone boot starts again", () =>
{
    // Same boot (cable replugged, PC app restarted): continue from the stored sequence.
    Equal(false, PhoneLogPoller.NeedsRestart("b1", 40L, "b1", 40L), "same boot, nothing new");
    Equal(false, PhoneLogPoller.NeedsRestart("b1", 40L, "b1", 55L), "same boot, new lines");
    // Phone app restarted: another boot id, even if its counter is already past the stored one.
    Equal(true, PhoneLogPoller.NeedsRestart("b1", 40L, "b2", 40L), "new boot, same number");
    Equal(true, PhoneLogPoller.NeedsRestart("b1", 40L, "b2", 900L), "new boot, larger number");
    // First poll ever (no stored boot) also starts from 0 for that boot.
    Equal(true, PhoneLogPoller.NeedsRestart(null, 0L, "b1", 7L), "first poll");
    // An old phone without the boot header: fall back to the sequence check.
    Equal(false, PhoneLogPoller.NeedsRestart("b1", 40L, null, 41L), "no header, forward");
    Equal(true, PhoneLogPoller.NeedsRestart("b1", 40L, null, 3L), "no header, backwards");
});

Test("clear logs: deletes only *.log in the given folder", () =>
{
    string tempDir = Path.Combine(Path.GetTempPath(), "pdanext-clear-logs-" + Guid.NewGuid().ToString("N"));
    try
    {
        Directory.CreateDirectory(tempDir);
        string pcLog = Path.Combine(tempDir, "pdanext-20261016.log");
        string pcPart = Path.Combine(tempDir, "pdanext-20261016.1.log");
        string phoneLog = Path.Combine(tempDir, "phone-20261016.log");
        string phoneFailures = Path.Combine(tempDir, "phone-failures.log");
        string otherTxt = Path.Combine(tempDir, "important.txt");
        string archiveZip = Path.Combine(tempDir, "diag.zip");
        string lockedLog = Path.Combine(tempDir, "active.log");

        File.WriteAllText(pcLog, "pc log");
        File.WriteAllText(pcPart, "pc log part 1");
        File.WriteAllText(phoneLog, "phone log");
        File.WriteAllText(phoneFailures, "phone failures");
        File.WriteAllText(otherTxt, "do not delete text file");
        File.WriteAllText(archiveZip, "do not delete zip file");

        // Keep locked.log open with exclusive lock
        int deleted;
        using (var stream = new FileStream(lockedLog, FileMode.Create, FileAccess.ReadWrite, FileShare.None))
        {
            deleted = FileLogger.ClearLogs(tempDir);
        }

        Equal(4, deleted, "deleted count should be 4 (pcLog, pcPart, phoneLog, phoneFailures)");
        Equal(false, File.Exists(pcLog), "pcLog should be deleted");
        Equal(false, File.Exists(pcPart), "pcPart should be deleted");
        Equal(false, File.Exists(phoneLog), "phoneLog should be deleted");
        Equal(false, File.Exists(phoneFailures), "phoneFailures should be deleted");
        Equal(true, File.Exists(otherTxt), "important.txt must NOT be deleted");
        Equal(true, File.Exists(archiveZip), "diag.zip must NOT be deleted");
        Equal(true, File.Exists(lockedLog), "locked.log was in use and must NOT be deleted");
    }
    finally
    {
        if (Directory.Exists(tempDir)) Directory.Delete(tempDir, true);
    }
});

Test("phone status json parses protected and killSwitch fields", () =>
{
    var s = PhoneStatus.Parse(
        "{\"app\":\"pdanext\",\"version\":\"3.0.0\",\"bytesUp\":100,\"bytesDown\":200,\"activeTcp\":1," +
        "\"activeUdp\":1,\"totalConnections\":2,\"failedConnections\":0,\"uplinkConnected\":true,\"uplink\":\"wifi\"," +
        "\"vpnActive\":true,\"protected\":true,\"killSwitch\":true,\"device\":\"test-phone\"}");
    Equal(true, s.Protected, "protected");
    Equal(true, s.KillSwitch, "killSwitch");

    var s2 = PhoneStatus.Parse(
        "{\"app\":\"pdanext\",\"version\":\"3.0.0\",\"bytesUp\":0,\"bytesDown\":0,\"activeTcp\":0," +
        "\"activeUdp\":0,\"totalConnections\":0,\"failedConnections\":0,\"uplinkConnected\":false,\"uplink\":\"none\"," +
        "\"vpnActive\":false,\"protected\":false,\"killSwitch\":false,\"device\":\"test-phone\"}");
    Equal(false, s2.Protected, "protected false");
    Equal(false, s2.KillSwitch, "killSwitch false");
});

Test("phone status json parses iranRanges field", () =>
{
    var s = PhoneStatus.Parse(
        "{\"app\":\"pdanext\",\"version\":\"3.0.0\",\"iranRanges\":624}");
    Equal(624, s.IranRanges, "iranRanges");

    var s2 = PhoneStatus.Parse(
        "{\"app\":\"pdanext\",\"version\":\"3.0.0\"}");
    Equal(0, s2.IranRanges, "iranRanges default 0");
});

Test("kill switch firewall: generated command strings", () =>
{
    string blockCmd = KillSwitchFirewall.BuildAddBlockRuleCommand();
    Equal(true, blockCmd.Contains("New-NetFirewallRule"), "has New-NetFirewallRule");
    Equal(true, blockCmd.Contains("-DisplayName 'PdaNext Kill Switch - Block Outbound'"), "has DisplayName");
    Equal(true, blockCmd.Contains("-Group 'PdaNext Kill Switch'"), "has Group");
    Equal(true, blockCmd.Contains("-Direction Outbound"), "has Direction");
    Equal(true, blockCmd.Contains("-Action Block"), "has Action");
    Equal(true, blockCmd.Contains("-Profile Any"), "has Profile");

    string queryCmd = KillSwitchFirewall.BuildQueryRulesCommand();
    Equal(true, queryCmd.Contains("Get-NetFirewallRule -Group 'PdaNext Kill Switch'"), "query Group");

    string removeCmd = KillSwitchFirewall.BuildRemoveRulesCommand();
    Equal(true, removeCmd.Contains("Remove-NetFirewallRule"), "remove command");
    Equal(true, removeCmd.Contains("-Group 'PdaNext Kill Switch'"), "remove Group");
    Equal(true, removeCmd.Contains("-ErrorAction SilentlyContinue"), "remove SilentlyContinue");
});

Test("kill switch firewall: apply and remove sequence", () =>
{
    var executed = new List<string>();
    KillSwitchFirewall.CommandRunner = cmd =>
    {
        executed.Add(cmd);
        return new ProcessResult { ExitCode = 0, Output = "OK" };
    };

    var logs = new List<string>();
    KillSwitchFirewall.Apply(logs.Add);

    Equal(true, KillSwitchFirewall.IsActive, "IsActive after Apply");
    // Removes old rules first, then adds the single block rule (allow rules cannot override a block)
    Equal(2, executed.Count, "2 commands on apply");
    Equal(true, executed[0].StartsWith("Remove-NetFirewallRule"), "1st is remove");
    Equal(true, executed[1].Contains("-Action Block"), "2nd is block rule");

    executed.Clear();
    KillSwitchFirewall.Remove(logs.Add);

    Equal(false, KillSwitchFirewall.IsActive, "IsActive false after Remove");
    Equal(1, executed.Count, "1 command on remove");
    Equal(true, executed[0].StartsWith("Remove-NetFirewallRule"), "remove called");
});

Test("kill switch firewall: leftover rules are detected after a crash or reboot", () =>
{
    KillSwitchFirewall.CommandRunner = cmd => new ProcessResult { ExitCode = 0, Output = "present\r\n" };
    Equal(true, KillSwitchFirewall.RulesPresent(), "present");
    KillSwitchFirewall.CommandRunner = cmd => new ProcessResult { ExitCode = 0, Output = "absent\r\n" };
    Equal(false, KillSwitchFirewall.RulesPresent(), "absent");
    KillSwitchFirewall.CommandRunner = cmd => new ProcessResult { ExitCode = 1, Output = "" };
    Equal(false, KillSwitchFirewall.RulesPresent(), "query failed");
});

Test("xray protocol: requests the phone understands", () =>
{
    Equal("{\"id\":null}", XrayProtocol.BuildSelectJson(null), "select off");
    Equal("{\"id\":\"prof_1\"}", XrayProtocol.BuildSelectJson("prof_1"), "select one");
    Equal("[\"a.com\",\"b \\\"x\\\".ir\"]", XrayProtocol.BuildBypassJson(new[] { " a.com ", "", "b \"x\".ir" }), "bypass escaped, blanks dropped");

    string body = "{\"id\":\"é\"}"; // non-ASCII: Content-Length counts bytes, not chars
    string req = XrayProtocol.BuildHttpRequest("POST", "/xray/select", body);
    Equal(true, req.StartsWith("POST /xray/select HTTP/1.1\r\n"), "request line");
    Equal(true, req.Contains("Content-Length: " + System.Text.Encoding.UTF8.GetByteCount(body) + "\r\n"), "byte length");
    Equal(true, req.EndsWith("\r\n\r\n" + body), "body after the blank line");
    Equal(false, XrayProtocol.BuildHttpRequest("GET", "/xray/profiles").Contains("Content-Length"), "GET has no body");
});

Test("xray protocol: responses from the phone", () =>
{
    Equal(true, XrayProtocol.TryParseHttpResponse("HTTP/1.1 400 Bad Request\r\nA: b\r\n\r\nBad Request: unknown profile", out int code, out string text), "parsed");
    Equal(400, code, "status");
    Equal("Bad Request: unknown profile", text, "body");

    var list = XrayProtocol.ParseProfilesJson("[{\"id\":\"p1\",\"name\":\"DE \\\"fast\\\"\",\"selected\":false},{\"id\":\"p2\",\"name\":\"NL\",\"selected\":true}]");
    Equal(2, list.Count, "two profiles");
    Equal("DE \"fast\"", list[0].Name, "escaped name");
    Equal(false, list[0].Selected, "first not selected");
    Equal(true, list[1].Selected, "second selected");
    Equal(0, XrayProtocol.ParseProfilesJson("[]").Count, "empty list");

    Equal(42L, XrayProtocol.ParseDelayResultJson("{\"ms\":42}"), "delay");
    Equal(null, XrayProtocol.ParseDelayResultJson("{\"ms\":null}"), "delay failed");
    var ids = XrayProtocol.ParseStringArrayJson("[\"prof_a\",\"prof_b\"]");
    Equal(2, ids.Count, "imported ids");
});

string relayPort = Environment.GetEnvironmentVariable("RELAY_PORT");
if (!string.IsNullOrEmpty(relayPort))
{
    Test("fetch /status from a live relay", () =>
    {
        var s = PhoneStatus.Fetch(int.Parse(relayPort));
        Equal("3.0.0", s.Version);
    });

    Test("router link: handshake, then ping the router through framed packets", () =>
    {
        using (var client = new TcpClient("127.0.0.1", int.Parse(relayPort)))
        {
            var stream = client.GetStream();
            stream.ReadTimeout = 5000;
            var hello = RouterLinkProtocol.Handshake(stream);
            Equal("192.168.42.1", hello.Gateway4.ToString());
            Equal("192.168.42.2", hello.Client4.ToString());
            Equal("255.255.255.0", hello.Mask4);
            Equal(9000, hello.Mtu);

            byte[] echo = IcmpEcho(hello.Client4, hello.Gateway4, 0x1234, 7);
            RouterLinkProtocol.WriteFrameHeader(stream, echo.Length);
            stream.Write(echo, 0, echo.Length);

            var buffer = new byte[65535];
            int length;
            while ((length = RouterLinkProtocol.ReadFrame(stream, buffer)) == 0) { }
            Equal(1, buffer[9], "protocol");          // ICMP
            Equal(0, (int)buffer[20], "icmp type");   // echo reply
            Equal(0x1234, (buffer[24] << 8) | buffer[25], "icmp id");
            Equal("192.168.42.1", new IPAddress(new[] { buffer[12], buffer[13], buffer[14], buffer[15] }).ToString(), "source");
        }
    });
}

Test("VpnDiscovery: parse cmd package query-services (Android 14 / Pixel 7)", () =>
{
    // Real output sample from Android 14 (Google Pixel 7, API 34) running:
    // adb shell cmd package query-services -a android.net.VpnService
    string sample =
        "3 services found:\n" +
        "  Service #0:\n" +
        "    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\n" +
        "    ServiceInfo:\n" +
        "      name=com.v2ray.ang.service.V2RayVpnService\n" +
        "      packageName=com.v2ray.ang\n" +
        "      labelRes=0x7f100021 nonLocalizedLabel=null icon=0x7f0c0000 banner=0x0\n" +
        "  Service #1:\n" +
        "    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\n" +
        "    ServiceInfo:\n" +
        "      name=com.github.kr328.clash.service.TunService\n" +
        "      packageName=com.github.kr328.clash\n" +
        "      labelRes=0x0 nonLocalizedLabel=Clash Meta icon=0x7f0800a2 banner=0x0\n" +
        "  Service #2:\n" +
        "    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\n" +
        "    ServiceInfo:\n" +
        "      name=com.wireguard.android.backend.GoBackend$VpnService\n" +
        "      packageName=com.wireguard.android\n" +
        "      labelRes=0x7f110023 nonLocalizedLabel=WireGuard icon=0x7f0e0000 banner=0x0\n";

    var apps = VpnDiscovery.ParseQueryServices(sample);
    Equal(3, apps.Count, "apps count");
    Equal("com.v2ray.ang", apps[0].Package);
    Equal("v2rayNG", apps[0].Label); // Resolved via KnownLabels
    Equal("com.github.kr328.clash", apps[1].Package);
    Equal("Clash Meta", apps[1].Label); // Extracted from nonLocalizedLabel
    Equal("com.wireguard.android", apps[2].Package);
    Equal("WireGuard", apps[2].Label); // Extracted from nonLocalizedLabel
});

Test("VpnDiscovery: parse pm query-services fallback (Android 11 / Samsung One UI 3.1)", () =>
{
    // Real output sample from Android 11 (Samsung Galaxy S20, One UI 3.1, API 30) running:
    // adb shell pm query-services -a android.net.VpnService
    string sample =
        "2 services found:\n" +
        "  Service #0:\n" +
        "    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\n" +
        "    ServiceInfo:\n" +
        "      name=app.hiddify.com.service.HiddifyVpnService\n" +
        "      packageName=app.hiddify.com\n" +
        "      labelRes=0x7f120034 nonLocalizedLabel=null icon=0x7f080080 banner=0x0\n" +
        "  Service #1:\n" +
        "    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\n" +
        "    ServiceInfo:\n" +
        "      name=io.nekohasekai.sagernet.service.SagerNetService\n" +
        "      packageName=io.nekohasekai.sagernet\n" +
        "      labelRes=0x7f0f0012 nonLocalizedLabel=null icon=0x7f080064 banner=0x0\n";

    var apps = VpnDiscovery.ParseQueryServices(sample);
    Equal(2, apps.Count, "apps count");
    Equal("app.hiddify.com", apps[0].Package);
    Equal("Hiddify", apps[0].Label);
    Equal("io.nekohasekai.sagernet", apps[1].Package);
    Equal("SagerNet", apps[1].Label);
});

Test("VpnDiscovery: parse dumpsys package fallback (Android 7.1 Nougat)", () =>
{
    // Real output snippet from Android 7.1 Nougat running:
    // adb shell dumpsys package
    string sample =
        "Service Resolver Table:\n" +
        "  Non-Data Actions:\n" +
        "      android.net.VpnService:\n" +
        "        41e6e9b8 com.v2ray.ang/.service.V2RayVpnService filter 41e6ebb8\n" +
        "        41e6f120 com.wireguard.android/.backend.GoBackend$VpnService filter 41e6f320\n" +
        "  MIME Typed Actions:\n";

    var apps = VpnDiscovery.ParseDumpsysPackageServices(sample);
    Equal(2, apps.Count, "apps count");
    Equal("com.v2ray.ang", apps[0].Package);
    Equal("v2rayNG", apps[0].Label);
    Equal("com.wireguard.android", apps[1].Package);
    Equal("WireGuard", apps[1].Label);
});

Test("VpnDiscovery: parse active VPN from dumpsys connectivity (Android 14)", () =>
{
    // Real output snippet from Android 14 (Pixel 7) dumpsys connectivity with v2rayNG active
    string sample =
        "Current Networks:\n" +
        "  NetworkAgentInfo{network{100} handle{430489568341} ni{NetworkInfo: type: VPN[], state: CONNECTED/CONNECTED, reason: (unspecified), extra: \"com.v2ray.ang\", failover: false, available: true, roaming: false} lp{{InterfaceName: tun0 LinkAddresses: [ 172.19.0.1/30 ] Routes: [ 0.0.0.0/0 -> 0.0.0.0 tun0 ] DnsAddresses: [ 1.1.1.1 ]}} nc{[ Transports: VPN Capabilities: NOT_RESTRICTED&TRUSTED&NOT_VPN LinkUpBandwidth: 1024000Kbps LinkDnBandwidth: 1024000Kbps Specifier: <null> OwnerUid: 10156]} factorySerialNumber=0}\n" +
        "  NetworkAgentInfo{network{101} handle{430489568342} ni{NetworkInfo: type: WIFI[], state: CONNECTED/CONNECTED, reason: (unspecified), extra: \"HomeNet\"} lp{{InterfaceName: wlan0}} nc{[ Transports: WIFI OwnerUid: 1000]}}\n" +
        "\n" +
        "VPNs:\n" +
        "  User 0:\n" +
        "    com.v2ray.ang\n" +
        "      Legacy: false\n" +
        "      Interface: tun0\n" +
        "      Addresses: 172.19.0.1/30\n" +
        "      Routes: 0.0.0.0/0\n";

    string active = VpnDiscovery.ParseActiveVpnPackage(sample);
    Equal("com.v2ray.ang", active);
});

Test("VpnDiscovery: parse active VPN from dumpsys connectivity (Android 10)", () =>
{
    // Real output snippet from Android 10 (Xiaomi MIUI 12) dumpsys connectivity with Clash Meta active
    string sample =
        "Current Networks:\n" +
        "  NetworkAgentInfo{network{354} ni{VPN CONNECTED extra: VPN:com.github.kr328.clash} lp{{InterfaceName: tun0 LinkAddresses: [ 198.18.0.1/16 ]}} nc{[ Transports: CELLULAR|VPN Capabilities: INTERNET&NOT_RESTRICTED OwnerUid: 10204]} ...}\n" +
        "  NetworkAgentInfo{network{350} ni{MOBILE CONNECTED extra: internet} lp{{InterfaceName: rmnet_data0}} nc{[ Transports: CELLULAR ]}}\n" +
        "\n" +
        "VPNs:\n" +
        "  User 0:\n" +
        "    Package: com.github.kr328.clash\n";

    string active = VpnDiscovery.ParseActiveVpnPackage(sample);
    Equal("com.github.kr328.clash", active);
});

Test("VpnDiscovery: parse dumpsys connectivity with NO active VPN", () =>
{
    // Real output snippet from Android 14 dumpsys connectivity when disconnected from all VPNs
    string sample =
        "Current Networks:\n" +
        "  NetworkAgentInfo{network{101} handle{430489568342} ni{NetworkInfo: type: WIFI[], state: CONNECTED/CONNECTED, reason: (unspecified), extra: \"OfficeWifi\"} lp{{InterfaceName: wlan0}} nc{[ Transports: WIFI Capabilities: INTERNET&NOT_RESTRICTED OwnerUid: 1000]}}\n" +
        "\n" +
        "VPNs:\n" +
        "  User 0:\n";

    string active = VpnDiscovery.ParseActiveVpnPackage(sample);
    Equal(null, active);
});

Test("VpnDiscovery: MergeAndMarkActive sorts active VPN first", () =>
{
    var list = new List<VpnApp>
    {
        new VpnApp { Package = "com.wireguard.android", Label = "WireGuard" },
        new VpnApp { Package = "com.v2ray.ang", Label = "v2rayNG" },
        new VpnApp { Package = "com.github.kr328.clash", Label = "Clash Meta" },
    };

    var merged = VpnDiscovery.MergeAndMarkActive(list, "com.v2ray.ang");
    Equal(3, merged.Count);
    Equal("com.v2ray.ang", merged[0].Package);
    Equal(true, merged[0].IsActive);
    Equal(false, merged[1].IsActive);
    Equal(false, merged[2].IsActive);
});

Test("VpnDiscovery: MergeAndMarkActive adds missing active package", () =>
{
    var list = new List<VpnApp>
    {
        new VpnApp { Package = "com.wireguard.android", Label = "WireGuard" },
    };

    var merged = VpnDiscovery.MergeAndMarkActive(list, "com.v2ray.ang");
    Equal(2, merged.Count);
    Equal("com.v2ray.ang", merged[0].Package);
    Equal(true, merged[0].IsActive);
    Equal("v2rayNG", merged[0].Label);
    Equal("com.wireguard.android", merged[1].Package);
    Equal(false, merged[1].IsActive);
});

Test("VpnDiscovery: GetAppLabel fallback formatting for unknown app", () =>
{
    Equal("My Custom Vpn", VpnDiscovery.GetAppLabel("org.test.my_custom_vpn"));
    Equal("Simple Proxy", VpnDiscovery.GetAppLabel("com.foobar.SimpleProxy"));
    Equal("1.1.1.1 + WARP", VpnDiscovery.GetAppLabel("com.cloudflare.onedotonedotonedotone"));
});

Test("Scrcpy: BuildArguments formats start-app and title correctly", () =>
{
    string args = Scrcpy.BuildArguments("R58N12ABCDE", "com.v2ray.ang", "v2rayNG");
    Equal("--serial R58N12ABCDE --start-app=com.v2ray.ang --window-title \"v2rayNG - PdaNext\" --stay-awake", args);

    string generalArgs = Scrcpy.BuildArguments("R58N12ABCDE", null, null);
    Equal("--serial R58N12ABCDE --window-title \"Phone - PdaNext\" --stay-awake", generalArgs);
});

Test("Scrcpy: values from the phone cannot inject arguments", () =>
{
    string args = Scrcpy.BuildArguments("R58 --record x.mp4", "com.x --no-control", "Evil\" --record \"y.mp4");
    Equal("--window-title \"Evil --record y.mp4 - PdaNext\" --stay-awake", args);
});

Console.WriteLine($"--- {passed} passed, {failed} failed ---");
return failed == 0 ? 0 : 1;

static byte[] IcmpEcho(IPAddress src, IPAddress dst, int id, int seq)
{
    var p = new byte[20 + 8 + 4];
    p[0] = 0x45; p[3] = (byte)p.Length; p[8] = 64; p[9] = 1;
    src.GetAddressBytes().CopyTo(p, 12); dst.GetAddressBytes().CopyTo(p, 16);
    Put16(p, 10, Checksum(p, 0, 20));
    p[20] = 8; Put16(p, 24, id); Put16(p, 26, seq); p[28] = (byte)'p'; p[29] = (byte)'i'; p[30] = (byte)'n'; p[31] = (byte)'g';
    Put16(p, 22, Checksum(p, 20, 12));
    return p;
}

static int Checksum(byte[] b, int off, int len)
{
    long sum = 0;
    for (int i = off; i < off + len; i += 2) sum += (b[i] << 8) | (i + 1 < off + len ? b[i + 1] : 0);
    while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
    return (int)(~sum & 0xFFFF);
}

static void Put16(byte[] b, int i, int v) { b[i] = (byte)(v >> 8); b[i + 1] = (byte)v; }
