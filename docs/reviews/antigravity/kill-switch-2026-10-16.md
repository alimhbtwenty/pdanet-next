# PdaNext Kill Switch Manual Verification & Implementation Review

**Date:** 2026-10-16  
**Branch:** `antigravity/kill-switch-2026-10-16`  
**Base:** `origin/ccr-65b98b7a-8b3t20`  

---

## 1. Overview and Architecture

The **Kill Switch** prevents the Windows PC from leaking traffic or reaching the internet outside the protected path:

1. **Phone Side (`core/router`):**
   - Config flag `requireProtection: Boolean` (default `false`).
   - `protectionMode`: `ProtectionMode.EXTERNAL_VPN` or `ProtectionMode.XRAY`.
   - `isProtectedProvider: () -> Boolean`:
     - In `EXTERNAL_VPN` mode: phone's external VPN is active (`vpnActive == true`).
     - In `XRAY` mode: Xray tunnel is connected.
   - When `requireProtection == true` and protection is down:
     - All new non-domestic flows are rejected immediately:
       - **TCP SYN packets:** Answered with TCP **RST** (`PacketBuilder.rst(original)`).
       - **UDP packets:** Answered with ICMP **Destination Unreachable: Port Unreachable** (Type 3, Code 3 via `PacketBuilder.portUnreachable(gateway4, original)`).
     - **Bypass Policy:** Domestic DIRECT flows (`BypassPolicy.DIRECT`) are permitted to proceed **only in Xray mode**. In External VPN mode, all internet traffic without VPN is blocked.
     - **Rate-Limited Logging:** Blocked connection attempts are logged at most once per minute in `tick()`, with aggregate counts of blocked TCP and UDP attempts.
   - **Status Reporting:** `GET /status` returns two new JSON fields:
     - `"protected": true | false`
     - `"killSwitch": true | false`

2. **Windows Side (`windows/src`):**
   - **Configuration:** `AppSettings.KillSwitch` stored in `HKCU\Software\PdaNext\KillSwitch` (default `false`).
   - **Firewall Isolation:** When active, applies rules under group `"PdaNext Kill Switch"` using Windows Defender Firewall:
     - Block all outbound traffic for all profiles (`-Direction Outbound -Action Block -Profile Any`).
     - Whitelist `adb.exe` outbound (`-Program <path_to_adb.exe> -Action Allow`).
     - Whitelist loopback traffic outbound (`-RemoteAddress '127.0.0.1','::1' -Action Allow`).
   - **Link-Drop Triggering:** If `AppSettings.KillSwitch` is on and connection is disrupted:
     - USB cable unplugged.
     - Phone app stopped / killed.
     - Network tunnel crashed / died.
     The bridge applies the firewall rules, tears down the tunnel, enters `BridgeState.Blocked` ("Kill switch active - internet blocked"), and displays the exact reason in the UI.
   - **Removal Paths:**
     - Deliberate disconnect (user clicks Disconnect button or tray menu).
     - User turns off the "Kill switch" checkbox.
     - User clicks the dedicated "Unblock" button.
     - App clean exit (`Bridge.Dispose`).
     - Successful reconnection to the phone.
   - **Crash Recovery (`Bridge.RepairAfterCrash`):**
     - If the previous session crashed or terminated unexpectedly:
       - If `KillSwitch` is on: preserves / reapplies firewall rules, enters `BridgeState.Blocked` with reason `"Previous session crashed"`, and shows the "Unblock" button.
       - If `KillSwitch` is off: cleans up any stale firewall rules and restores normal networking.

---

## 2. Automated Test Verification Results

### A. Phone-Side Core Tests (`gradle -p core test`)
- `PacketBuilder.portUnreachable`: Verified ICMP Type 3, Code 3 packet generation including IP header, ICMP header, and original IP header + first 8 bytes of payload.
- `RouterTest`:
  - `kill switch: unprotected SYN gets RST, UDP gets ICMP port unreachable`: Verified TCP SYN receives RST packet, UDP packet receives ICMP port unreachable.
  - `kill switch: domestic traffic allowed only in xray mode`: Verified domestic IP in XRAY mode is allowed DIRECT, while in EXTERNAL_VPN mode it is blocked when VPN is down.
  - `kill switch: rate-limited logging once per minute`: Verified aggregate blocked counts are logged in 1-minute intervals.
- `RelayServerTest`:
  - Verified `GET /status` contains `"protected"` and `"killSwitch"` booleans without breaking existing fields.

### B. Windows-Side Logic Tests (`dotnet run --project windows/tests`)
- `PhoneStatus.Parse`: Verified deserialization of `"protected"` and `"killSwitch"` fields.
- `KillSwitchFirewall`:
  - Verified command generation for Block Outbound, Allow ADB, Allow Loopback, and Remove Rules.
  - Verified `Apply` and `Remove` execution sequence with mocked `CommandRunner` (clears old rules first, sets `IsActive`).

### C. Build Validation (`dotnet build windows/PdaNext.csproj -c Release`)
- Compiled successfully with 0 warnings and 0 errors into `windows\out\bin\PdaNext\Release\PdaNext.exe`.

---

## 3. Manual Testing Step-by-Step Procedures

### Scenario 1: Phone Router Kill Switch Verification (Protection Down)
1. Launch PdaNext on Android phone and Windows PC.
2. In PdaNext phone settings or test harness, set `requireProtection = true` with `ProtectionMode.EXTERNAL_VPN`.
3. Disconnect any active VPN on the phone (`vpnActive == false`).
4. On the PC, open a browser or command prompt and attempt:
   ```cmd
   curl -I http://example.com
   ```
5. **Expected result:**
   - TCP connections are immediately closed with RST (connection reset by peer).
   - UDP DNS requests outside the router receive ICMP port unreachable.
   - Inspect log via `GET http://127.0.0.1:28080/log`: shows rate-limited blocked attempt count.
   - `GET http://127.0.0.1:28080/status` shows `"protected": false, "killSwitch": true`.

---

### Scenario 2: Domestic Bypass in Xray Mode
1. On phone, configure `ProtectionMode.XRAY` with `requireProtection = true`, and domestic IP ranges in `BypassPolicy`.
2. Keep Xray tunnel disconnected (`isProtectedProvider() == false`).
3. Send traffic from PC to a domestic IP (e.g., `10.0.0.1` or Iranian CIDR in bypass list).
4. **Expected result:**
   - Domestic connection is accepted and routed DIRECT.
5. Send traffic to a foreign/external IP.
6. **Expected result:**
   - Blocked with TCP RST / ICMP port unreachable.

---

### Scenario 3: Windows Firewall Kill Switch on Cable Unplug
1. Open `PdaNext.exe` on Windows.
2. Check the box **"Kill switch (block internet if the phone link drops)"**.
3. Plug in the Android phone with USB debugging enabled. Click **"Connect"**.
4. Verify state reaches **"Connected"** (status dot turns green).
5. Open PowerShell and verify no PdaNext kill switch firewall rules are present:
   ```powershell
   Get-NetFirewallRule -Group "PdaNext Kill Switch" -ErrorAction SilentlyContinue
   ```
   *(Expected: no output)*
6. While connected, physically unplug the USB cable.
7. **Expected result:**
   - The UI dot turns red (`Theme.Error`).
   - Title displays **"Kill switch active"**.
   - Subtitle displays: `"Phone unplugged. Kill switch active - internet blocked to prevent fallback to Wi-Fi/Ethernet."`
   - The **"Unblock"** button appears beside the Connect button.
   - Run in PowerShell:
     ```powershell
     Get-NetFirewallRule -Group "PdaNext Kill Switch"
     ```
     Three rules exist:
     - `PdaNext Kill Switch - Block Outbound` (Action: Block, Direction: Outbound)
     - `PdaNext Kill Switch - Allow ADB` (Action: Allow, Program: adb.exe)
     - `PdaNext Kill Switch - Allow Loopback` (Action: Allow, RemoteAddress: 127.0.0.1, ::1)
   - Try browsing `https://www.google.com` or pinging `1.1.1.1` on local Wi-Fi/Ethernet:
     - Request is blocked by Windows Firewall.
   - ADB communication still works: `adb devices` responds normally.

---

### Scenario 4: Windows Firewall Kill Switch Unblock and Toggle
1. From the blocked state in Scenario 3, click the **"Unblock"** button.
2. **Expected result:**
   - Firewall rules are immediately removed.
   - Status changes to **"Not connected"** with detail `"Unblocked: normal networking restored"`.
   - "Unblock" button hides.
   - Local Wi-Fi / Ethernet internet connectivity is restored immediately.
   - Verify in PowerShell:
     ```powershell
     Get-NetFirewallRule -Group "PdaNext Kill Switch" -ErrorAction SilentlyContinue
     ```
     *(Expected: no rules found)*
3. Reconnect by plugging in cable and clicking **"Connect"**.
4. Unplug cable again (entering **"Kill switch active"**).
5. Uncheck the checkbox **"Kill switch (block internet if the phone link drops)"**.
6. **Expected result:**
   - Turning off the checkbox immediately removes the firewall rules and unblocks the PC.

---

### Scenario 5: Windows App Crash Recovery
1. Check the **"Kill switch"** checkbox and connect to the phone.
2. Force-kill the PdaNext process via Task Manager or PowerShell:
   ```powershell
   Stop-Process -Name PdaNext -Force
   ```
3. Because the process was killed abruptly, `AppSettings.ActiveSession` remains set.
4. Relaunch `PdaNext.exe`.
5. **Expected result:**
   - `Bridge.RepairAfterCrash()` detects the unclean shutdown and that `KillSwitch` is enabled.
   - Windows Firewall rules remain active (re-applied).
   - UI dot is red, showing **"Kill switch active"** with detail `"Previous session crashed. Kill switch active - internet blocked."`.
   - The **"Unblock"** button is displayed.
   - Clicking **"Unblock"** removes the rules and restores standard networking.

---

## 4. Key Firewall Commands Reference

To inspect or manually audit the Windows Firewall rules at any time:

```powershell
# View all rules in the PdaNext Kill Switch group
Get-NetFirewallRule -Group "PdaNext Kill Switch" | Format-Table Name, DisplayName, Direction, Action, Enabled

# Test outbound network connection (should fail when active)
Test-NetConnection -ComputerName 1.1.1.1 -Port 443

# Emergency manual cleanup (if ever needed)
Remove-NetFirewallRule -Group "PdaNext Kill Switch" -ErrorAction SilentlyContinue
```
