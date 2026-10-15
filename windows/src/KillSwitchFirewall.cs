using System;
using System.Collections.Generic;

namespace PdaNext
{
    /// <summary>
    /// Manages Windows Defender Firewall rules for the kill switch.
    /// When the phone link drops, all outbound traffic is blocked except loopback
    /// and the adb process, preventing the PC from falling back to unprotected Wi-Fi/Ethernet.
    /// All rules are grouped under rule group "PdaNext Kill Switch".
    /// </summary>
    public static class KillSwitchFirewall
    {
        public const string RuleGroupName = "PdaNext Kill Switch";

        public static bool IsActive { get; set; }

        /// <summary>Injectable command runner for testing.</summary>
        public static Func<string, ProcessResult> CommandRunner = PowerShell;

        public static string BuildAddBlockRuleCommand() =>
            "New-NetFirewallRule -DisplayName 'PdaNext Kill Switch - Block Outbound' " +
            "-Group '" + RuleGroupName + "' -Direction Outbound -Action Block -Profile Any";

        public static string BuildAddAllowAdbCommand(string adbPath) =>
            "New-NetFirewallRule -DisplayName 'PdaNext Kill Switch - Allow ADB' " +
            "-Group '" + RuleGroupName + "' -Direction Outbound -Action Allow -Program '" + (adbPath ?? Adb.ExePath) + "'";

        public static string BuildAddAllowLoopbackCommand() =>
            "New-NetFirewallRule -DisplayName 'PdaNext Kill Switch - Allow Loopback' " +
            "-Group '" + RuleGroupName + "' -Direction Outbound -Action Allow -RemoteAddress '127.0.0.1','::1'";

        public static string BuildRemoveRulesCommand() =>
            "Remove-NetFirewallRule -Group '" + RuleGroupName + "' -ErrorAction SilentlyContinue";

        public static List<string> BuildApplyCommands(string adbPath = null) => new List<string>
        {
            BuildAddBlockRuleCommand(),
            BuildAddAllowAdbCommand(adbPath),
            BuildAddAllowLoopbackCommand(),
        };

        public static void Apply(Action<string> log, string adbPath = null)
        {
            try
            {
                // Remove any previous rules in the group first to prevent duplicates
                Remove(log);

                var commands = BuildApplyCommands(adbPath);
                foreach (var cmd in commands)
                {
                    var r = CommandRunner(cmd);
                    if (!r.Ok && log != null) log("Kill switch firewall rule warning: " + r.Output.Trim());
                }
                IsActive = true;
                log?.Invoke("Kill switch: Windows Firewall rules applied (group: " + RuleGroupName + ")");
            }
            catch (Exception e)
            {
                log?.Invoke("Kill switch: Failed to apply firewall rules: " + e.Message);
            }
        }

        public static void Remove(Action<string> log)
        {
            try
            {
                var r = CommandRunner(BuildRemoveRulesCommand());
                if (!r.Ok && log != null) log("Kill switch firewall cleanup warning: " + r.Output.Trim());
                IsActive = false;
                log?.Invoke("Kill switch: Windows Firewall rules removed");
            }
            catch (Exception e)
            {
                log?.Invoke("Kill switch: Failed to remove firewall rules: " + e.Message);
            }
        }

        static ProcessResult PowerShell(string command) =>
            ProcessRunner.Run("powershell.exe",
                "-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"" + command.Replace("\"", "\\\"") + "\"",
                30000);
    }
}
