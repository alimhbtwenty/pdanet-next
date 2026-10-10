# Recon map: PdaNet+ (Android app + Windows desktop client)

Scope: USB mode — share the phone's internet with one Windows PC over a USB cable, for all PC traffic.
For: the repo owner's own use (personal tool, free and open source).
Date: 2026-10-10

Clean-room: built only from public listings, reviews and forum posts. No APK
or driver was decompiled, no network traffic of the original was captured.
pdanet.co, play.google.com and support.connectify.me could not be fetched
from the research environment, so some rows below rely on search snippets and are
marked with lower confidence.

## Sources

| # | source | URL | notes |
| --- | --- | --- | --- |
| 1 | APKMirror release pages (description, release notes) | https://www.apkmirror.com/apk/june-fabrics-technology-inc/pdanet/ | USB works on almost all phones; "Hide Tether Usage" note for T-Mobile/MetroPCS (v4.19) |
| 2 | Google Play listing | https://play.google.com/store/apps/details?id=com.pdanet | free edition = time-limited continuous use, otherwise identical |
| 3 | AppBrain listing | https://www.appbrain.com/app/pdanet/com.pdanet | modes: USB, Bluetooth, WiFi Direct |
| 4 | FileHorse (Windows client) | https://www.filehorse.com/download-pdanet/ | vendor statement: the phone may be on mobile data, WiFi "or even through VPN" and PdaNet shares it |
| 5 | Connectify support article | https://support.connectify.me/article/495-tether-pdanet-wifi | WiFi flow (not fetched, title only) |
| 6 | GrapheneOS forum, "tether thru VPN" | https://discuss.grapheneos.org/d/2916-tether-thru-vpn/18 | Android's *native* USB tethering bypasses the phone VPN |
| 7 | HardForum PPTP + PdaNet thread | https://hardforum.com/threads/pptp-vpn-w-pdanet-anyone.1580323/ | vendor advice: connect the VPN on the phone, not the PC |
| 8 | CloudsPress review | https://www.cloudspress.com/?p=613330 | USB needs desktop client + driver; best for one laptop, sustained use |

## Core loop

Plug phone in → tap "Connect" → the whole PC is online through the phone's connection, and stays online.

## How the original works (inferred)

- **Phone side**: a user-space app relays connections. Because the sockets are
  opened *by an app on the phone*, the phone's own routing applies: mobile data,
  WiFi, or an active VPN (source 4, 7). TTL looks native for the same reason.
- **PC side**: a desktop client installs a virtual network adapter driver, so
  Windows sees a normal network connection and *every* app uses it, not just
  browsers. The adapter talks to the phone app over USB debugging (ADB).
  (confidence: medium — consistent with "needs drivers" in source 8 and the
  driver page referenced from the release notes.)
- **Why it beats native USB tethering for this use case**: native tethering
  forwards packets at the kernel level and bypasses the phone VPN (source 6).
  A relay app does not.

That last point is the main design rule for PdaNet-Next: **relay in user space
on the phone, capture everything with a virtual adapter on the PC.**

## Screens

| ID | screen | route / how to reach | purpose | key components | states seen |
| --- | --- | --- | --- | --- | --- |
| S01 | Android main | app launcher | start/stop USB mode, other modes | mode toggles, status text, help link | off, on, error (no USB debugging) |
| S02 | Android settings/menu | overflow menu | hide tether usage, help, purchase | checkbox list | — |
| S03 | Windows tray menu | tray icon right-click | connect / disconnect / settings | context menu, status line | disconnected, connecting, connected |
| S04 | Windows connection balloon | after connect | confirm state | toast | connected, failed |
| S05 | Windows driver / setup | installer | install adapter + ADB driver | wizard | installed, needs driver |

## Flows

```
F01 Get the PC online through the phone over USB
    S01 (tap USB mode) -> S03 (Connect USB) -> S04 connected
    happy path clicks: 3 (+ one-time USB debugging prompt)
    edge: USB debugging off, unauthorized ADB, two phones attached,
          cable unplugged mid-session, phone screen off / Doze,
          phone VPN reconnecting, PC already on another network
F02 Disconnect cleanly
    S03 (Disconnect) -> PC networking back to normal
    edge: client crashed while connected (settings must still be restored)
F03 First-time setup
    S05 -> enable USB debugging on phone -> F01
```

## Components

| component | variants | states | used on |
| --- | --- | --- | --- |
| Big connect toggle | on / off | idle, connecting, connected, error | S01, Windows window |
| Status line | text + color | offline, online, error | S01, S03 |
| Tray icon | connected / disconnected | — | S03 |
| Toast / balloon | info, warning, error | — | S04 |

## Inferred data model

```
Session   mode (usb | wifi | bt), device serial, started_at, bytes_up, bytes_down, state
          evidence: S01/S03 states, review mentions of data counters
          confidence: medium
Settings  hide_tether (bool), autostart (bool), port
          evidence: S02, release notes
          confidence: medium
```

## Feature matrix

See `features.csv`. Must: 10, should: 6, could: 3, skip: 3.

## Out of scope

- WiFi Direct / Bluetooth modes (user asked for USB; WiFi Direct is broken on recent Android anyway).
- Mac client, paid licensing, FoxFi companion.
- A signed kernel NDIS driver: replaced by Wintun (signed, used by WireGuard) via sing-box.

## Size

Screens 5, flows 3, entities 2. Hard parts: full-system capture on Windows
(TUN + routes + DNS without leaks), UDP over an ADB link that only carries TCP,
restoring PC network settings after a crash. Size: M.
