# Health check — state of `main` at 734c544

Reviewed: every file in `android/`, `windows/`, the workflow and the docs.
Severity: **blocker** = the feature cannot work, **high** = breaks in normal
use, **medium** = breaks in some cases, **low** = cleanup.

## Blockers

1. **Wrong ADB direction — the connection never works.** The phone app listens
   on the phone's `127.0.0.1:8080`, and Windows points its proxy at the PC's
   `127.0.0.1:8080`. The scripts and `PdaNext.cs` run `adb reverse tcp:8080
   tcp:8080`, which does the opposite: it makes the *phone's* port 8080 connect
   to the *PC*. Nothing listens on the PC's port 8080, so every request fails
   (and the reverse rule collides with the app's own listener on the phone).
   The right command is `adb forward tcp:<pc port> tcp:<phone port>`.
2. **Only proxy-aware apps are bridged.** WinINet/WinHTTP proxy settings are
   honored by browsers and some apps. Games, most UDP, DNS, `ping`, VPN
   clients, many CLIs and anything that ignores the system proxy bypass it.
   The requirement is the whole PC.
3. **No UDP at all.** The engine is an HTTP proxy (CONNECT + plain HTTP). DNS,
   QUIC, calls and games cannot pass.

## High

4. **Tunnels die after 30 s of silence.** `soTimeout = 30000` on both sides of
   a CONNECT tunnel; an idle WebSocket, SSH or long-poll connection throws a
   timeout inside `pipe()` and is torn down.
5. **Plain HTTP keep-alive is broken.** After forwarding the first request
   the raw byte pipe keeps going, so a second request on the same client
   connection (often to a different host, in absolute form) is sent to the
   first host as-is. `Connection: close` is not enforced.
6. **PC loses internet if the client crashes or is killed.** The system proxy
   stays set to a dead port. Nothing restores it on next start.
7. **User proxy settings are overwritten, not restored.** Connect writes
   `ProxyServer`/`ProxyOverride`; disconnect deletes them and sets
   `ProxyEnable=0`. A user with a corporate proxy loses it.
8. **Release build fails.** `isMinifyEnabled = true` with a
   `proguard-rules.pro` that does not exist. (CI only builds debug, which
   hides it.)

## Medium

9. Watchdog runs `adb devices` on the UI thread every 4 s — the tray menu
   freezes whenever ADB is slow.
10. More than one device (or an emulator) attached → every `adb` call fails
    with "more than one device"; there is no `-s <serial>`.
11. Android activity state is lost on rotation/recreate: the service is still
    running but the button says START.
12. `POST_NOTIFICATIONS` is never requested on Android 13+, so the foreground
    notification is invisible.
13. Global Windows TTL is changed (`defaultcurhoplimit=65`) and later forced
    to 128 rather than restored to what it was. In proxy/relay mode the PC's
    TTL never reaches the carrier, so the change is useless as well as
    invasive.
14. The Windows `.exe` in the repo is a committed binary; CI does not build
    it from `src/`, so there is no proof it matches the source.

## Low / docs

15. README and ARCHITECTURE describe things that do not exist: `dashboard/`,
    `setup-adb.ps1`, a VpnService, DoH resolver, JA3 handling, QR pairing.
16. `wintun.dll` is shipped but nothing uses it.
17. Release notes name a zip (`pdanext-windows11-scripts.zip`) that the
    workflow does not produce.

## What was fine

- Phone listener is bound to `127.0.0.1` only, so the relay is not open to
  the WiFi/LAN.
- Header size and count limits on the HTTP parser.
- Foreground service + partial wake lock is the right approach for Doze.

## Fix plan (implemented on this branch)

- New phone engine (`core/`, pure Kotlin/JVM, unit-tested off-device): a userspace NAT router
  that receives the PC's raw IP packets over the USB link (TCP, UDP, ping, DNS). No idle timeout
  on connections. The proxy approach is gone: nothing on the PC is configured as a proxy.
- PC: "phone as router" mode with a Wintun adapter (gateway and DNS = the phone, default route,
  NRPT rule against DNS leaks). `adb forward`, per-device serial, background watchdog,
  crash-safe cleanup of the DNS rule, no TTL changes.
- Phone VPN: every flow is re-created by the phone app's own sockets, so Android sends it through
  any active VPN. The app shows whether a VPN is active.
