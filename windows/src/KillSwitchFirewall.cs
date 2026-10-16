using System;
using System.Collections.Generic;

namespace PdaNext
{
    /// <summary>
    /// Manages Windows Defender Firewall rules for the kill switch.
    /// When the phone link drops, all outbound traffic is blocked so the PC cannot fall back to
    /// unprotected Wi-Fi/Ethernet. adb keeps working (it talks over USB, not the network stack) and
    /// loopback is not filtered by Windows Firewall, so reconnecting needs no allow rules (they would
    /// not help anyway: a block rule always wins over an allow rule).
    /// All rules are grouped under rule group "PdaNext Kill Switch"; the group itself is the
    /// persisted state, so a crash or a reboot while blocked is detected with <see cref="RulesPresent"/>.
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

        public static string BuildRemoveRulesCommand() =>
            "Remove-NetFirewallRule -Group '" + RuleGroupName + "' -ErrorAction SilentlyContinue";

        public static string BuildQueryRulesCommand() =>
            "if (Get-NetFirewallRule -Group '" + RuleGroupName + "' -ErrorAction SilentlyContinue) { 'present' } else { 'absent' }";

        public static List<string> BuildApplyCommands() => new List<string> { BuildAddBlockRuleCommand() };

        public static void Apply(Action<string> log)
        {
            try
            {
                // Remove any previous rules in the group first to prevent duplicates
                Remove(null);

                foreach (var cmd in BuildApplyCommands())
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
                bool was = IsActive;
                IsActive = false;
                if (was) log?.Invoke("Kill switch: Windows Firewall rules removed");
            }
            catch (Exception e)
            {
                log?.Invoke("Kill switch: Failed to remove firewall rules: " + e.Message);
            }
        }

        /// <summary>Asks Windows whether the rule group exists (e.g. left by a crash or a reboot while blocked).</summary>
        public static bool RulesPresent()
        {
            try
            {
                var r = CommandRunner(BuildQueryRulesCommand());
                return r.Ok && r.Output.Trim() == "present";
            }
            catch
            {
                return false;
            }
        }

        static ProcessResult PowerShell(string command) =>
            ProcessRunner.Run("powershell.exe",
                "-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"" + command.Replace("\"", "\\\"") + "\"",
                30000);
    }
}
