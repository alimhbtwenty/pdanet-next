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
- **Never tested on a real phone + Windows PC yet.** First real test: ask for the Windows log
  ("Show log") and the phone screen state.

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

## Current work (v3.1, since 2026-10-14) - read this first
- **Work branch:** `ccr-65b98b7a-8b3t20` (not merged to `main`). Design: `docs/decisions/0001-xray-engine-and-iran-bypass.md`.
- **Owner's goals for v3.1** (their words, decided; do not relitigate):
  - Embed the **Xray engine** in the phone app; the phone only carries the PC's traffic to a VPN server
    with the owner's configs (vless, vmess, trojan, ws/grpc/xhttp/reality). **No `VpnService`, no proxy,
    no TUN, no local listener**; Xray is called in-process (`core.Dial`/`core.DialUDP`), outbounds only.
  - **Iranian sites bypass inside PdaNext**: `BypassPolicy` (Iranian IPv4 list, `.ir`, owner's list,
    addresses learned from direct DNS answers) sends them out of the phone's own network. DNS split.
    No fallback to direct when the tunnel is down.
  - Configs managed from the PC (list, switch, latency test, import links).
  - **Logging system** and **kill switch**.
  - Later: "any VPN app" mode: the PC lists the phone's VPN apps (`cmd package query-services -a
    android.net.VpnService`) and opens one in an embedded **scrcpy** (`--start-app=<pkg>`, `ADB` env =
    PdaNext's adb). In that mode the bypass cannot be done by PdaNext (v2rayNG never calls `allowBypass()`).
- **Way of working:** the owner wants **Antigravity agents to do the heavy work** and Claude only to
  supervise (save Claude credits). Rules for agents: `AGENTS.md`, `.agent/rules/pdanext.md`, `GEMINI.md`.
  Agents work on `antigravity/<role>-<date>` branches through **draft PRs into `ccr-65b98b7a-8b3t20`**;
  CI (`release.yml`) runs on every push, including the Linux e2e and the Windows build/tests.
  Claude subscribes to the PRs, reviews (run the tests, read the diff), comments on the PR, and merges
  the branches into the work branch when green. Never merge an agent branch unreviewed.
- **State 2026-10-16 (later):** the triage defects in `docs/reviews/antigravity/claude-triage-2026-10-16.md`
  were fixed by Claude directly on the agent branches (PRs #7 #8 #9, all CI green, review comments on
  each PR). The three were merged in order (bypass-wiring, logging, kill-switch) with conflicts resolved
  on **`integrate/v3.1`** (= work branch + the three). Kill switch design after the fix: one outbound
  block rule in group "PdaNext Kill Switch"; the group itself is the persisted state (queried on start);
  the MSI removes it on uninstall. Pending owner approval: fast-forward the work branch to
  `integrate/v3.1`, merge PR #6 (`fix/ping-pc-firewall`, 3.0.1) to `main`, close PR #5
  (`preview/new-version`, based on v2.0.8, obsolete). After 3.0.1 is on `main`, merge `main` into the
  work branch (VERSION and this file will conflict; take 3.0.1 and keep both handoff notes).
  Merges need the owner's approval in this environment (the auto-mode guard blocks `gh pr merge`).
  Sessions get confused when they start from `main`: this section exists only on the work branch.
- **State 2026-10-16 (evening):** `integrate/v3.1` now also holds the VPN picker, the Iranian IP list
  (#11), log retention (#13), the Xray engine (#12) and PC server management (#14), all CI green;
  the owner merges PR #10 (`integrate/v3.1` -> work branch) on GitHub. Task briefs: `docs/tasks/`.
  - **Logs (owner's rules):** nothing is deleted automatically; Windows "Clear logs" button (confirm)
    deletes `*.log` in `%LOCALAPPDATA%\PdaNext\logs`; the PC copies phone logs (`/log?since`, boot id
    in `X-Log-Boot`, state in `phone-log-state.txt`) to `phone-YYYYMMDD.log`; the phone keeps only
    connection failures (`level >= WARN`) in `files/logs/failures*.log`, never deleted, copied to the
    PC on connect/disconnect.
  - **Xray:** `xraybridge/` (Go 1.27.2, libXray v1.261010.0, gomobile with `-checklinkname=0` and
    16 KB pages) -> AAR in CI; `XrayEngineImpl` on Android; `CloseWrite` is a no-op on purpose (Xray
    does not deliver FIN; ending the uplink would trigger its 1 s downlink-only cut). APK ~26 MB.
    `XrayControl` + Keystore-encrypted store; `/xray/` endpoints (loopback); PC "Servers" window.
  - **Not done / known limits:** nothing tested on the phone yet; kill switch XRAY mode not tied to
    the selected server; PC list parser skips server names containing `{`/`}`; Windows main window is
    920 px tall.
  - **Antigravity agents:** run with `agy -p ... --dangerously-skip-permissions` (needs the session in
    Manual mode; the auto-mode guard blocks it). Their credits (Claude and Gemini models) ran out on
    2026-10-16 after ~3 tasks. Quality: fine for bounded tasks, every branch still had at least one
    real defect found only by review; keep router/Xray/protocol work with Claude.
  - **Local toolchain on the laptop** (user folder, checksums verified): Go `C:\Users\Alimhb\tools\go1.27.2`,
    JDK 17 `C:\Users\Alimhb\tools\jdk17`, Gradle 8.10.2 `C:\Users\Alimhb\tools\gradle`. `dl.google.com`
    is blocked from this network (use `mirrors.aliyun.com/golang` + the go.dev checksum). No .NET SDK.
- **Remote Control:** the laptop (`DESKTOP-RGERDBN`) can run `claude remote-control`
  (`claude.cmd` if PowerShell blocks scripts), but a cloud session needs the owner's trusted-device
  approval to start sessions there; the owner chose the GitHub PR loop instead.
- Laptop: Windows, VS Code with Claude Code, Antigravity, `gh` logged in as `alimhbtwenty`, repo at
  `C:\Users\Alimhb\PdaNext\pdanet-next`. The laptop's internet comes through PdaNext itself.

## Open work / ideas
- **Wi-Fi / hotspot mode** (owner wants it in a later version): PC app connects to the phone's Wi-Fi
  IP instead of adb; pairing (QR/PIN); prefer `LocalOnlyHotspot` (unverified); router code unchanged.
- Code signing for the MSI (SmartScreen warns today).
- Efficiency audit (wake/Wi-Fi locks only while a PC is attached, fewer adb spawns):
  `docs/plans/v3.1-bypass-v2ray-efficiency.md` section 4.
- Real-device test pass; then tune MTU/window if throughput is low.

## Environment gotchas (Claude Code cloud container)
- `dl.google.com` blocked: no local Android SDK; rely on CI for the APK. GitHub release downloads
  blocked too; Go module proxy works (`GOTOOLCHAIN=auto go install ...`).
- `apt-get install dotnet-sdk-8.0 iproute2 iputils-ping` works; netns/veth/TUN work as root; the
  kernel has no IPv6 policy routing.
- Maven Central 429s: retry with backoff. Unset proxy env vars in network tests.
- `pkill -f` can kill your own shell; stale relays on port 28080 cause confusing test results.
- Pushing a branch whose PR was merged: restart it from `origin/main` and push with
  `--force-with-lease=refs/heads/<branch>:<old-sha>`.
