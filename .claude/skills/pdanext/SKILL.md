---
name: pdanext
description: Handoff for the PdaNext project (Android phone as a Windows PC's router over USB, PC traffic follows the phone's VPN) - owner preferences, what was built and why, decisions already made, release state, open work and environment gotchas. Use at the start of any session about PdaNext / pdanet-next / pdanext, tethering the PC through the phone, or when the owner refers to earlier work ("the router", "the MSI", "the logo", "the networking skill").
---

# PdaNext handoff

Compressed from the session that built v3 (2026-10-10). Code-level notes: repo `CLAUDE.md`.
Technical field notes: the `networking` skill.

## Owner
- GitHub `alimhbtwenty`. Writes in Persian (often casual, short); answer in Persian, keep code/docs English.
- Typical flow: "PR باز کن و با main ادغامش کن" = open a PR, wait for CI green, merge. They expect
  you to decide technical details yourself ("خودت بهش فک کن") and report plainly.
- Real goal: the PC must appear to the internet with the phone's **VPN IP**, and apps (Antigravity)
  must not detect a proxy. Mental model: "the phone is a router connected to a VPN; I can ping the
  router and the PC; both use the VPN's public IP."
- Declined for now: Persian UI, IPv6 option, proxy mode (removed on request).
- Wanted later: the same over the phone's **hotspot / Wi-Fi** (see Open work).

## Repos and state
- `alimhbtwenty/pdanet-next` (owner was asked to rename it to `pdanext` in GitHub Settings; GitHub
  redirects the old URL). The owner asked for a single clean release **v3.0.0** (`PdaNext-Setup.msi`,
  `PdaNext-Windows.zip`, `PdaNext.apk`); old v2.0.x/v3.0.15/v3.0.19 tags were deleted, their release
  pages had to be deleted by the owner (the session token cannot). Version source: root `VERSION`;
  a release is published once per version. CI = `.github/workflows/release.yml` (relay tests, Linux
  e2e, APK, Windows build + logic tests vs live relay + MSI build/install/uninstall, release on main).
- Stale branch `claude/rename-pdanext-fix-tunnel` (another session, proxy-based) was deleted by the owner.
- Repo About text still describes the old proxy design; owner was given new text to paste.
- `alimhbtwenty/whatsapp-attendance-bot`: unrelated PWA. Branch `ccr-bd34aa07-k9vhv4` there holds 50
  third-party "antigravity" skills under `.claude/skills` (unmerged; owner said they are not
  repo-specific). The skills zip came from the owner; it was scanned and looked benign.
- **First real-device test passed (2026-10-16)**, owner's Xiaomi M2007J3SY (Mi 10T) + Windows 11,
  run from a local Claude Code session on that PC (adb, gh, PdaNext installed; no .NET SDK there,
  shell not elevated). PC exit IP = phone VPN IP, no IPv6 leak, DNS/UDP/ping fine, PC throughput =
  the phone's own (VPN-bound). Only bug: phone->PC ping blocked by the Public firewall profile,
  fixed in 3.0.1 (firewall rule in `NetConfig`). The Windows log is UI-only (no file); the
  `/status` endpoint via the adb forward and `adb logcat --pid` are the useful diagnostics.

## What was built (in order) and why
1. Health check of v2: `adb reverse` instead of `adb forward` (could never connect), proxy only
   (no UDP/DNS/games), 30 s idle kills, broken keep-alive, crash left PC offline, user proxy
   overwritten, release build broken. See `docs/HEALTH-CHECK.md`.
2. Replica recon of PdaNet+ (public sources only, `replica/`): it relays in user space on the
   phone + virtual adapter on the PC. Key insight: kernel tethering bypasses the VPN, app sockets don't.
3. v3.0 first cut: SOCKS5 + UDP-over-TCP relay + sing-box TUN on Windows. Worked (e2e green) but
   could not ping, and fake-IP DNS is detectable. Replaced.
4. Router mode (current): raw IP over USB, userspace NAT on the phone (TCP termination, UDP, ICMP
   echo via unprivileged ping socket, DNS via `DnsResolver.rawQuery`), Wintun adapter on Windows,
   NRPT against DNS leaks, AAAA/HTTPS withheld against IPv6 leaks, phone→PC ping, live pings in UI.
5. Rename to **PdaNext** (package `io.github.alimhbtwenty.pdanext`), proxy mode removed.
6. Logo (P = phone + USB cable/plug, violet→orange; `tools/logo/draw_logo.py`), MSI (WiX 5),
   `LICENSE` (MIT), `networking` skill, this handoff (`CLAUDE.md` + `pdanext` skill).
7. Versioning reset to 3.0.0 from one `VERSION` file.

## Decisions (do not relitigate without a reason)
| Topic | Decision | Why |
| --- | --- | --- |
| Transport | `adb forward tcp:28080 tcp:28080`, phone binds loopback | no drivers, works on all phones, not exposed on Wi-Fi |
| PC capture | Wintun adapter, metric 1 default route | all apps; invisible as a proxy; adapter dies with the process |
| Phone side | userspace NAT in a foreground service, no root | the only way for PC traffic to follow the phone VPN without root |
| DNS | phone resolver; NRPT `.` → 192.168.42.1; AAAA/HTTPS → no data | no leaks via other adapters or IPv6 |
| Ping | gateway answers; internet via Android ping socket | owner's router mental model |
| Not possible w/o root | traceroute TTL-exceeded, GRE/raw ESP, other devices on hotspot without an app | stated to the owner |
| Windows stack | .NET Framework 4.8 (in-box), x64, `requireAdministrator`, logon task for autostart | no runtime install |

## Open work / ideas
- **Wi-Fi / hotspot mode** (owner wants it in a later version): PC app connects to the phone's Wi-Fi
  IP instead of adb; phone listens on Wi-Fi with pairing (QR/PIN challenge); host route for the
  phone via Wi-Fi; prefer `LocalOnlyHotspot` (no carrier tethering check, unverified); router code
  unchanged.
- Kill switch (keep PC offline when the link drops) - offered, not requested yet.
- Code signing for the MSI (SmartScreen warns today).
- Verify "Ping PC" on the device after installing 3.0.1. Throughput needs no tuning so far.

## Environment gotchas (Claude Code cloud container)
- `dl.google.com` blocked: no local Android SDK; rely on CI for the APK. GitHub release downloads
  blocked too; Go module proxy works (`GOTOOLCHAIN=auto go install ...`).
- `apt-get install dotnet-sdk-8.0 iproute2 iputils-ping` works; netns/veth/TUN work as root; the
  kernel has no IPv6 policy routing.
- Maven Central 429s: retry with backoff. Unset proxy env vars in network tests.
- `pkill -f` can kill your own shell; stale relays on port 28080 cause confusing test results.
- Pushing a branch whose PR was merged: restart it from `origin/main` and push with
  `--force-with-lease=refs/heads/<branch>:<old-sha>`.
