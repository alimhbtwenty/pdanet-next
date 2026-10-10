using System;
using System.Collections.Generic;
using System.IO;
using PdaNetNext;

// Tests for the parts of the Windows app that do not need Windows.
//   dotnet run --project windows/tests
// Optional, when available:
//   SING_BOX=/path/to/sing-box   validates the generated config with `sing-box check`
//   RELAY_PORT=28080             fetches /status from a running relay (gradle -p core installDist)

// `--emit-config <host> <port>` prints the config the app would write, for tests/e2e.
if (args.Length == 3 && args[0] == "--emit-config")
{
    Console.Write(SingBoxConfig.Build(int.Parse(args[2]), keepLanDirect: true, relayHost: args[1]));
    return 0;
}

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
        "{\"app\":\"pdanet-next\",\"version\":\"3.0.0\",\"bytesUp\":5004433,\"bytesDown\":55029029,\"activeTcp\":3," +
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

string singBox = Environment.GetEnvironmentVariable("SING_BOX");
foreach (bool lan in new[] { true, false })
{
    Test("generated sing-box config is valid (keepLanDirect=" + lan + ")", () =>
    {
        string json = SingBoxConfig.Build(28080, lan);
        if (!json.Contains("\"udp_over_tcp\"") || !json.Contains("\"server_port\": 28080")) throw new Exception("missing relay outbound");
        if (string.IsNullOrEmpty(singBox)) { Console.WriteLine("      (SING_BOX not set: schema check skipped)"); return; }
        string path = Path.Combine(Path.GetTempPath(), "pdanet-next-test-" + lan + ".json");
        File.WriteAllText(path, json);
        var r = ProcessRunner.Run(singBox, "check -c " + path, 30000);
        if (!r.Ok) throw new Exception("sing-box check: " + r.Output);
        if (r.Output.Contains("WARN")) throw new Exception("sing-box warnings: " + r.Output);
    });
}

string relayPort = Environment.GetEnvironmentVariable("RELAY_PORT");
if (!string.IsNullOrEmpty(relayPort))
{
    Test("fetch /status from a live relay", () =>
    {
        var s = PhoneStatus.Fetch(int.Parse(relayPort));
        Equal("3.0.0", s.Version);
    });
}

Console.WriteLine($"--- {passed} passed, {failed} failed ---");
return failed == 0 ? 0 : 1;
