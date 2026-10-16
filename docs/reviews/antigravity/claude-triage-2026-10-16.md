# Triage of Antigravity branches, 2026-10-16

Base: `ccr-65b98b7a-8b3t20` (a3a4fc4). On the base: core unit tests green, Linux e2e 17/17,
Windows build OK, `windows/tests` 7/7 (with a live relay). All three branches start from the base.
Nothing is merged yet.

| Branch | Unit (core) | e2e | Windows build | windows/tests | Verdict |
| --- | --- | --- | --- | --- | --- |
| `antigravity/bypass-wiring-2026-10-16` | 24 pass | 17 pass, **3 fail** | not touched | not touched | fix needed |
| `antigravity/logging-2026-10-16` | 20 pass | 17/17 | **4 errors** | 10 pass | fix needed |
| `antigravity/kill-switch-2026-10-16` | 16 pass | 15 pass, **2 fail** | OK | 10 pass | fix needed |

## bypass-wiring
1. `tests/e2e/run-router-e2e.sh`: `fake_tunnel()` is defined after `serve_forever()`, so it never
   starts; it also binds 127.0.0.1 in the server namespace, which the relay cannot reach. Fails
   "tunnel TCP: foreign IP/domain routes through tunnel".
2. The desktop fake tunnel (`Main.kt`) answers every name with 203.0.113.20, which breaks the
   existing check "DNS: NXDOMAIN for unknown names".
3. `DirectUpstream.connectTcp` leaks the `Socket` when `connect()` throws (the old code closed it).

## logging
1. `windows/src/MainForm.cs:478` uses `PhoneStatus.RawJson`, which does not exist: the Windows app
   does not compile (CS1061 x4). The branch was never built.

## kill-switch
1. `RouterLink.run()` no longer calls `relay.attachRouter(this)` (and dropped `tcpNoDelay`). The
   relay never knows a PC is attached: `/status` reports `routerMode:false`, `/ping-pc` returns
   null, the phone UI never shows "PC connected". Fails e2e "phone pings the PC" and "traffic was
   carried by the router".
2. A blocked PC can stay offline with no UI: in the Blocked state `Teardown` clears
   `AppSettings.ActiveSession` and keeps the firewall rules; `KillSwitchFirewall.IsActive` is an
   in-memory flag. After a crash or a reboot, `RepairAfterCrash` sees `stale == null` and
   `IsActive == false` and returns early, so the rules stay and nothing explains why the internet
   is gone. The state must be persisted (or the rule group queried on start).
3. Windows Firewall block rules win over allow rules, so the "Allow ADB" and "Allow loopback" rules
   do nothing. Harmless today (adb uses USB, loopback is not filtered), but the comments and the
   report claim otherwise.
4. The MSI uninstall does not remove the "PdaNext Kill Switch" rule group: uninstalling while
   blocked leaves the PC offline.

## Merge order (after fixes)
bypass-wiring, then logging, then kill-switch. All three edit `Router.kt`, `RouterLink.kt` and
`RelayServer.kt`; logging and kill-switch both edit `Bridge.cs` and `MainForm.cs`. Conflicts are
resolved during the merge, not by the agents (no rebase, no force-push).
