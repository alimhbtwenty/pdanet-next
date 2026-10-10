# PdaNext: notes for Claude

An Android phone acts as a Windows PC's **router over USB**. The PC gets a Wintun adapter
(`192.168.42.2/24`, gateway and DNS `192.168.42.1` = the phone); raw IP packets cross the
adb-forwarded TCP stream; a userspace NAT router in the phone app re-creates every flow with the
app's own sockets, so the PC uses the phone's uplink **and its VPN**. No proxy anywhere (apps such as
Antigravity detect system proxies; an adapter behind a router is invisible).

More context: `.claude/skills/pdanext/SKILL.md` (history, decisions, owner preferences, open work),
`.claude/skills/networking/` (technical field notes), `ARCHITECTURE.md`, `docs/HEALTH-CHECK.md`.

## Layout
- `core/` Kotlin/JVM phone engine. `router/`: `Packets.kt` (IP/TCP/UDP/ICMP build+parse),
  `Router.kt` (dispatch, gateway pings/DNS, UDP/ICMP), `TcpSession.kt` (TCP termination),
  `UdpFlow.kt`, `MiniDns.kt`, `RouterLink.kt` (PDNR link). `RelayServer.kt`: loopback port 28080,
  routes `PDNR` to the router, serves `GET /status` and `GET /ping-pc`.
- `android/` app (`io.github.alimhbtwenty.pdanext`): `RelayService` (foreground service),
  `AndroidRouterPlatform` (ICMP ping socket, `DnsResolver.rawQuery`), `MainActivity`, `UplinkInfo`.
- `windows/` C# .NET Framework 4.8 WinForms, x64: `Bridge.cs` (single worker thread: adb, connect,
  watchdog, teardown, crash repair), `RouterTunnel.cs` + `Wintun.cs` (adapter + pumps),
  `NetConfig.cs` (netsh, metric 1, NRPT), `RouterLinkProtocol.cs`, `MainForm.cs`, `AppSettings.cs`.
  `installer/PdaNext.wxs` = WiX 5 MSI. `tests/` = platform-neutral logic tests.
- `tests/e2e/run-router-e2e.sh` + `tun_client.py`: Linux netns end-to-end (17 checks).
- `tools/logo/draw_logo.py` regenerates every icon. `replica/` = PdaNet+ recon.

## Commands
| | |
| --- | --- |
| Phone engine tests | `gradle -p core test` (Maven may return 429: retry) |
| Relay on desktop | `gradle -p core installDist && core/build/install/core/bin/core --bind 127.0.0.1 --port 28080` |
| Windows build (any OS, .NET 8 SDK) | `dotnet build windows/PdaNext.csproj -c Release` |
| Windows logic tests | `RELAY_PORT=28080 dotnet run --project windows/tests` (with the relay running) |
| End-to-end (Linux root) | `sudo tests/e2e/run-router-e2e.sh` after `installDist` |
| APK | CI only in cloud sessions (`dl.google.com` is blocked there) |
| MSI | CI only (WiX needs Windows) |

## Rules
- Link protocol (`PDNR`), router addresses, port 28080 and the status JSON (`"app":"pdanext"`) are
  shared by phone and PC: change both sides together and keep `windows/tests` + e2e green.
- Never reintroduce a proxy mode or fake-IP DNS; never change the PC's global TTL.
- The router withholds AAAA/HTTPS DNS answers on purpose (prevents IPv6 leaking around the phone).
- Version lives in the root `VERSION` file only (Android, exe, MSI, release tag read it; a unit test
  checks `RelayServer.VERSION` matches). Work on a branch, open a PR, wait for CI green, merge to
  `main`. `main` publishes release `v<VERSION>` (`PdaNext-Setup.msi`, `PdaNext-Windows.zip`,
  `PdaNext.apk`) only if that tag does not exist yet: bump `VERSION` to ship a new release.
- The owner writes in Persian; reply in Persian. Code, commits and docs in English.
