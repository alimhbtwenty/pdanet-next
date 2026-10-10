---
name: networking
description: Field notes for building and debugging networking between Android phones and PCs - USB/ADB links, sharing a phone's connection (and its VPN) with a computer, userspace NAT routers, TUN/Wintun adapters, Windows routing/DNS-leak handling, proxy vs. TUN trade-offs, and how to test network code with Linux network namespaces. Use when working on tethering, VPN routing, packet tunnels, TCP/UDP/ICMP relays, adapter configuration, or end-to-end network tests.
---

# Networking

Hard-won notes from building PdaNext (an Android phone acting as a Windows PC's router over USB,
with the phone's VPN applying to the PC). Each topic lives in `references/`; read only the ones the
task needs. Facts were verified by tests unless marked *(unverified)*.

## Topics

| File | Read when |
| --- | --- |
| `references/android-routing-and-vpn.md` | Phone traffic, tethering, hotspot, VPN, what apps/ADB shell/root can do |
| `references/adb-usb-link.md` | Using USB debugging as a transport (forward vs reverse, multi-device, autostart) |
| `references/userspace-nat-router.md` | Terminating a PC's raw IP packets on a phone: TCP/UDP/ICMP/DNS design |
| `references/windows-adapter-and-dns.md` | Wintun, netsh, default route, NRPT, IPv6 leaks, crash cleanup |
| `references/proxy-vs-tun.md` | Choosing between system proxy, SOCKS/UoT, TUN+fake-IP, raw-IP router; detection |
| `references/testing-network-code.md` | Proving network code works without the real devices (netns, veth, TUN) |
| `references/ci-and-packaging.md` | GitHub Actions for APK/Windows builds, adb/wintun downloads, WiX MSI |
| `references/ideas-and-open-questions.md` | Not built yet: Wi-Fi/hotspot transport, kill switch, root mode |

## Core rules (the short version)

1. **Android applies a VPN per app UID.** Sockets opened by an app follow the VPN; packets forwarded
   by the kernel (USB tethering, hotspot) do not. To put another device behind the phone's VPN
   without root, re-create its flows with the app's own sockets.
2. **`adb forward tcp:PC tcp:PHONE`** makes the PC's port reach the phone. `adb reverse` is the
   opposite. Always pass `-s <serial>`.
3. **A system proxy is visible to apps; a network adapter is not.** If apps must not "see" a proxy,
   use an adapter (TUN) and terminate traffic on the phone.
4. **Prove isolation before trusting a pass.** An end-to-end test needs a sanity check that the
   client cannot reach the target any other way, or passes are meaningless.

## Bundled scripts

- `scripts/tun_client.py`: Linux stand-in for a PC client: creates a TUN, does the PDNR link
  handshake, configures addresses/default route, pumps framed IP packets. Template for any
  "raw packets over a TCP stream" test.
- `scripts/netns_harness.sh`: skeleton of a two-namespace test bed (phone side, PC side, veth link,
  fake internet on a TEST-NET address) with the sanity check built in.

## Extending this skill

Add a new `references/<topic>.md` (start with a "When this matters" line, then facts, then pitfalls),
add a row to the table above, and keep the "Core rules" list to rules that held across projects.
Mark anything not proven by a test as *(unverified)*.
