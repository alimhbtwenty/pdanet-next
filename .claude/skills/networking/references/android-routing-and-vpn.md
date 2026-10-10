# Android routing, tethering and VPN

When this matters: anything that shares a phone's connection, or must make other traffic follow the
phone's VPN.

## How a VPN applies on Android
- VPN routing is per **app UID** (policy routing on uid ranges). A socket created by any app that is
  not excluded from the VPN goes through it, including raw-ish sockets like ICMP ping sockets and
  `DnsResolver` queries on the default network.
- Packets the kernel **forwards** (USB tethering, Wi-Fi hotspot, Bluetooth tethering) belong to no
  app, so they bypass the VPN. This is by design and matches user reports (GrapheneOS forum
  "tether thru VPN").
- Consequence: to give a PC the phone's VPN, terminate the PC's traffic in an app and re-open each
  flow with that app's sockets (what PdaNet+ does, and what PdaNext's router does).
- Users must not exclude the relay app in their VPN app's split-tunneling list.

## What each privilege level can do
| Level | Can | Cannot |
| --- | --- | --- |
| Normal app | TCP/UDP sockets, unprivileged ICMP echo (`SOCK_DGRAM` + `IPPROTO_ICMP`), `DnsResolver.rawQuery` (API 29+), foreground service | Raw IP sockets, forward packets, change routing/iptables |
| ADB shell (USB debugging) | install/start apps, `pm grant`, settings, often toggle tethering | iptables, routing tables, packet forwarding into a VPN |
| Root | everything: route tethered traffic into the VPN tun with iptables/ip rule | - |

So "USB debugging on" is a transport, not extra networking power.

## Useful Android APIs
- `ConnectivityManager.getNetworkCapabilities(activeNetwork)`: `TRANSPORT_VPN` tells you a VPN is the
  default network; cellular/Wi-Fi transport is also flagged underneath.
- `Settings.Global.ADB_ENABLED` to detect USB debugging.
- `android.system.Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP)` + `Os.sendto`/`Os.recvfrom`: ping
  without root; the kernel fills identifier and checksum. Use `SO_RCVTIMEO` via `Os.setsockoptTimeval`.
- `DnsResolver.getInstance().rawQuery(null, query, FLAG_EMPTY, executor, cancel, callback)`: forwards a
  raw DNS packet to the default network's resolver (all record types). Below API 29 fall back to
  `InetAddress.getAllByName` for A/AAAA.

## Keeping a relay alive
- Foreground service (`specialUse` type on Android 14+ with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
  property), partial wake lock, and a Wi-Fi low-latency lock if Wi-Fi may be the uplink.
- Request `POST_NOTIFICATIONS` on 13+ or the notification is invisible (the service still runs).
- Starting a foreground service from the background can throw on 12+ (e.g. sticky restarts): catch
  and stop instead of crashing.
- A PC tool can start the app with `adb shell am start -n <pkg>/.MainActivity --ez autostart true`.

## Pitfalls
- Hotspot/tethering enablement may be checked or blocked by carriers *(unverified per carrier)*.
- `LocalOnlyHotspot` creates a Wi-Fi network without sharing the uplink *(not yet tested)*.
