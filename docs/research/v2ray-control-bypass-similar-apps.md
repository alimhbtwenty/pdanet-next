# Research: domestic bypass, controlling the phone's v2ray from the PC, similar apps

Date: 2026-10-14. Replaces sections 1 and 2 of `docs/plans/v3.1-bypass-v2ray-efficiency.md`,
which misread the owner's request.

## What the owner asked for
- **Bypass:** traffic to Iranian destinations must leave with a domestic IP, not through the VPN.
  The router already breaks every flow on the phone, so the idea is to check the destination there
  and send domestic flows out normally.
- **v2ray:** the phone's VPN is **v2rayNG**. The PC only needs a **controller and viewer**: see the
  configs, switch between them, turn the VPN on and off, without touching the phone.
- **No proxy anywhere.** No proxy is enabled, nothing changes on the laptop's proxy settings, and
  no traffic passes through a proxy.
- scrcpy (mirrors the phone screen on the PC) may be used.

Everything below about v2rayNG, Clash Meta, Hiddify and scrcpy was checked in their source code
(v2rayNG 2.3.11 at commit of 2026-10-11, ClashMetaForAndroid, hiddify-app, scrcpy 5.0.1), not
guessed. Nothing was tested on a real phone.

## 1. Domestic bypass

### Why PdaNext cannot do it by itself while v2rayNG is the VPN
Android puts every app inside the active VPN. An app can send around the VPN (for example with
`Network.bindSocket` on the underlying network) **only if the VPN app called
`VpnService.Builder.allowBypass()`**. v2rayNG never calls it: its `CoreVpnService.kt` only uses
`addDisallowedApplication` / `addAllowedApplication` for per-app mode. So the PdaNext router has
two options under v2rayNG, and both are wrong:
- stay inside the VPN: every socket, domestic or not, goes through v2ray;
- be put in v2rayNG's "bypass apps" list: **all** PC traffic leaves without the VPN.

There is no third path without a proxy, which the owner ruled out.

### The working answer: v2rayNG's own Iran routing (no PdaNext code)
v2rayNG ships a routing preset, `assets/custom_routing_white_iran`:

| Rule | Outbound |
| --- | --- |
| UDP 443 (QUIC) | block |
| `geoip:private`, `geosite:private` | direct |
| `domain:ir`, `geosite:category-ir` | direct |
| `geoip:ir` | direct |

"direct" is Xray's freedom outbound inside v2rayNG's own process. That process is excluded from
its VPN, so these connections leave through the phone's normal network with a **domestic IP**.
PdaNext's sockets already go through v2rayNG, so the PC gets exactly the same behaviour for free.
This is the destination check the owner described, done one step later by v2ray instead of the
PdaNext router.

Steps (one time, in v2rayNG, or from the PC through scrcpy):
1. Settings → Routing settings → import the predefined "Iran" whitelist rule set.
2. Add the owner's own sites to the "Bypass Iran domains" rule as `domain:example.com` (this
   covers subdomains). A URL becomes its host name: `https://www.example.com/a/b` → `domain:example.com`.
3. Keep the geo files updated (v2rayNG: Settings → Geo asset files → update).

PdaNext's job is then only to **show** it works: a "domestic check" on the PC that fetches the
exit IP for one Iranian and one foreign test site and shows both (on request only).

### Later, only if the VPN app allows bypass
Clash Meta for Android has an "Allow bypass" setting (`TunService.kt` calls `allowBypass()` when
it is on). With such a VPN, the router could do the check itself: match the destination against
an Iranian CIDR list and bind that flow's socket to the underlying network. Not worth building
while the owner uses v2rayNG.

## 2. Controlling the phone's VPN from the PC

### What v2rayNG exposes to other apps (verified in source)
| Interface | What it does | Limits |
| --- | --- | --- |
| `TaskerReceiver` (exported, action `com.twofortyfouram.locale.intent.action.FIRE_SETTING`) | VPN on, VPN off, start a given config by `guid`; guid `Default` = toggle the current one | Needs a nested `Bundle` extra, which `adb shell am broadcast` cannot build; the PdaNext phone app can send it. **There is no way to list the configs or their guids**: they live in v2rayNG's private storage. |
| `TaskerActivity` (exported, `EDIT_SETTING`) | Shows v2rayNG's config picker and returns the chosen guid and name | Needs a tap on the picker, once per config. |
| `UrlSchemeActivity` (`v2rayng://install-config?url=…`, or share text) | Imports a config or subscription link | Import only. |
| Widget, quick-settings tile | Toggle | Toggle only. |

So a native v2rayNG controller can do: on/off, and switching between **favourites** whose guid was
picked once. It cannot show the full config list, ping results or the active config.

### Options
| | Option | What the owner gets | Phone changes | Size |
| --- | --- | --- | --- | --- |
| **A** | **"Phone screen" button in PdaNext → scrcpy** | v2rayNG's full UI on the PC: list, switch, ping test, import, routing rules | none | S |
| B | Native v2rayNG controls (Tasker receiver via the PdaNext phone app) | On/off and favourite configs as PC buttons | none | M |
| C | Switch the phone VPN to Hiddify or Clash Meta and use their controller API | Full native list, latency test, switching inside PdaNext | change of VPN app | M |

**A, scrcpy (recommended first).** scrcpy is Apache-2.0, needs no app on the phone and no root,
and works over the adb link PdaNext already uses. PdaNext can ship it in `bin/` and start it with
a button. Two points matter:
- Start it with the environment variable `ADB` pointing to PdaNext's own `adb.exe`
  (`app/src/adb/adb.c` reads `ADB`). Two different adb versions on one PC kill each other's
  server, which would drop the PdaNext link.
- Useful flags: `--serial <phone>`, `--turn-screen-off` (screen stays dark on the phone),
  `--stay-awake`, `--window-title "Phone"`.

**B, native v2rayNG buttons.** The PC sends a command over the existing link; the PdaNext phone
app sends the Tasker broadcast to `com.v2ray.ang/.receiver.TaskerReceiver`. To fill the favourites,
the phone app opens `TaskerActivity` once per config (through scrcpy, no phone in hand). Worth it
only if the owner wants one-click switching without the mirrored screen.

**C, a VPN app with a controller API.** Hiddify (sing-box based) has its Clash API **on by default
on port 16756**; Clash Meta for Android has a configurable `external-controller` plus exported
START/STOP/TOGGLE intents (`ExternalControlActivity`). Through `adb forward` the PC reaches that
API on the phone's loopback and can list groups and nodes, run latency tests and switch nodes.
This is a **control channel only**: no traffic passes through it and no proxy is set anywhere.
Both apps import vless/vmess/trojan links, so the owner's configs move over. Keep their "allow
connections from LAN" option off.

## 3. Similar apps

| App | How it shares the phone's connection | Follows the phone VPN | Ping | Open source | Notes |
| --- | --- | --- | --- | --- | --- |
| **PdaNext** | Userspace router over USB (adb), Wintun adapter on the PC | yes | yes | yes | No proxy, no driver |
| PdaNet+ | Relay on the phone, virtual adapter or proxy on the PC | yes (app sockets) | limited | no | Paid; see `replica/` |
| EasyTether | USB or Bluetooth, own PC driver | yes (app sockets) | — | no | Lite version blocks some connections |
| Tetrd | USB accessory mode (no USB debugging needed on non-Windows) | yes (app sockets) | **no** | no | Paid tiers, both directions |
| gnirehtet | The opposite direction: PC's internet to the phone | — | — | yes | Useful as code reference only |
| VPN Hotspot | Shares the VPN over the system hotspot / USB tethering | yes | yes | yes | **Needs root** |
| NetShare, Every Proxy | Proxy on the phone | yes | no | no | Proxy: ruled out |
| scrcpy | Mirrors and controls the phone screen over adb | — | — | yes | Fits option A above |

Sources for the competitor rows (listings and developer posts, not tested here):
nextpit Tetrd thread (https://nextpit.com/forum/801193/app-tetrd-usb-tethering-reverse-tethering-noroot),
Arch wiki Android tethering (https://wiki.archlinux.org/title/Android_tethering),
alternativeto.net pages for EasyTether and PdaNet.

What the comparison says for PdaNext:
- No other root-free tool gives the PC a real router (ping included) that follows the phone's VPN
  without a proxy. That stays the main point of the app.
- Tetrd's accessory-mode link (no USB debugging) is the one transport idea worth noting for later;
  it does not help on Windows today.
- Nobody bundles a VPN controller; option A or C would be new.

## Proposed next steps
1. **A:** "Phone screen" button with bundled scrcpy using PdaNext's adb. Size S.
2. Write the v2rayNG Iran routing steps into the README (Persian note for the owner) and add the
   on-request "domestic check" to the PC app. Size S.
3. Only on the owner's request: B (native on/off + favourites) or C (switch VPN app + native
   controller).
