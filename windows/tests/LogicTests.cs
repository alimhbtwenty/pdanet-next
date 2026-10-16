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

Test("file logger: cleans old logs older than retention period", () =>
{
    string tempDir = Path.Combine(Path.GetTempPath(), "pdanext-test-logs-" + Guid.NewGuid().ToString("N"));
    try
    {
        Directory.CreateDirectory(tempDir);
        string oldFile = Path.Combine(tempDir, "pdanext-20200101.log");
        File.WriteAllText(oldFile, "old logs");
        File.SetLastWriteTimeUtc(oldFile, DateTime.UtcNow.AddDays(-10));

        FileLogger.CleanOldLogs(tempDir);
        if (File.Exists(oldFile)) throw new Exception("Old log file was not cleaned");
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
