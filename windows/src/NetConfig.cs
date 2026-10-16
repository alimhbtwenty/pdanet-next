using System;
using System.Threading;

namespace PdaNext
{
    /// <summary>
    /// Windows network settings for the router adapter: the address the phone assigned, the phone as
    /// gateway and DNS server, lowest metric so it becomes the default route, and an NRPT rule so every
    /// DNS query goes to the phone (Windows otherwise also asks the DNS of its other networks).
    /// </summary>
    public static class NetConfig
    {
        const string NrptComment = "PdaNext";
        const string FirewallRule = "PdaNext: ping from the phone";

        public static void Apply(string adapter, RouterHello hello, Action<string> log)
        {
            string name = "name=\"" + adapter + "\"";
            // A freshly created adapter can take a moment to appear to netsh.
            ProcessResult r = null;
            for (int i = 0; i < 20; i++)
            {
                r = Netsh("interface ipv4 set address " + name + " source=static address=" + hello.Client4 +
                          " mask=" + hello.Mask4 + " gateway=" + hello.Gateway4 + " gwmetric=1 store=active");
                if (r.Ok) break;
                Thread.Sleep(250);
            }
            if (!r.Ok) throw new InvalidOperationException("Could not configure the adapter: " + r.Output.Trim());

            Check(Netsh("interface ipv4 set interface \"" + adapter + "\" metric=1 mtu=" + hello.Mtu + " store=active"), log);
            Check(Netsh("interface ipv4 set dnsservers " + name + " source=static address=" + hello.Gateway4 + " register=none validate=no"), log);

            if (AppSettings.PreventDnsLeaks)
            {
                RemoveNrpt();
                Check(PowerShell("Add-DnsClientNrptRule -Namespace '.' -NameServers '" + hello.Gateway4 + "' -Comment '" + NrptComment + "'"), log);
            }
            // Windows files the adapter under the Public profile, where inbound echo is blocked, so the
            // phone's "Ping PC" got no reply. Allow echo from the phone only, nothing else.
            RemoveFirewallRule();
            Check(Netsh("advfirewall firewall add rule name=\"" + FirewallRule + "\" dir=in action=allow protocol=icmpv4:8,any" +
                        " localip=" + hello.Client4 + " remoteip=" + hello.Gateway4 + " profile=any"), log);
            ProcessRunner.Run("ipconfig.exe", "/flushdns", 5000);
            log("Adapter " + adapter + ": " + hello.Client4 + "/" + hello.Prefix4 + ", gateway and DNS " + hello.Gateway4 + ", MTU " + hello.Mtu);
        }

        /// <summary>Undo what outlives the adapter (NRPT and firewall rules). Safe to call any time, e.g. after a crash.</summary>
        public static void Remove(Action<string> log)
        {
            var r = RemoveNrpt();
            if (!r.Ok) log?.Invoke("NRPT cleanup: " + r.Output.Trim());
            RemoveFirewallRule();
            ProcessRunner.Run("ipconfig.exe", "/flushdns", 5000);
        }

        // Fails harmlessly ("No rules match") when the rule is absent.
        static ProcessResult RemoveFirewallRule() => Netsh("advfirewall firewall delete rule name=\"" + FirewallRule + "\"");

        static ProcessResult RemoveNrpt() => PowerShell(
            "Get-DnsClientNrptRule | Where-Object { $_.Comment -eq '" + NrptComment + "' } | Remove-DnsClientNrptRule -Force");

        static ProcessResult Netsh(string args) => ProcessRunner.Run("netsh.exe", args, 15000);

        static ProcessResult PowerShell(string command) =>
            ProcessRunner.Run("powershell.exe", "-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"" + command.Replace("\"", "\\\"") + "\"", 30000);

        static void Check(ProcessResult r, Action<string> log)
        {
            if (!r.Ok) log("warning: " + r.Output.Trim());
        }
    }
}
