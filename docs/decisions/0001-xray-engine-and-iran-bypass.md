# ADR-0001: Xray engine inside the phone app, Iranian bypass in the router

Date: 2026-10-15. Status: accepted by the owner (design); implementation in progress.

## Owner's requirements (in their words, summarised)
- The phone is not used as a phone: it only carries the **PC's** traffic. The app must **not**
  tunnel the whole phone (no `VpnService`).
- The app embeds the **Xray engine** and connects the PC's traffic to a VPN server with the
  owner's configs (vless, vmess, trojan, ws/grpc/xhttp/reality transports, ...). v2rayNG is no
  longer needed.
- **No proxy and no TUN mode** anywhere: nothing on the laptop changes, no local SOCKS/HTTP
  listener, no traffic through a proxy.
- **Iranian sites are bypassed inside PdaNext itself**: they leave from the phone's own network
  with a domestic IP.
- Configs are managed from the PC (list, switch, latency test, import links), without touching
  the phone.

## Decision
The existing userspace router stays the only packet path. It already terminates every PC flow on
the phone; at that point it asks [`BypassPolicy`](../../core/src/main/kotlin/io/github/alimhbtwenty/pdanext/core/router/BypassPolicy.kt)
where the flow goes:

```
PC --USB--> router (terminates TCP/UDP)
              |-- Route.DIRECT  -> ordinary phone socket (domestic IP), as today
              '-- Route.TUNNEL  -> Xray core.Dial / core.DialUDP (in-process) -> outbound -> VPN server
```

- **Xray is called in-process**, with `core.Dial(ctx, instance, dest)` and `core.DialUDP`
  (Xray-core `core/functions.go`, marked `xray:api:stable` / `beta`). The Xray config has
  **outbounds only, no inbounds**: no listener, no proxy, no TUN device.
- **Iranian decision (DIRECT):** destination in the bundled Iranian IPv4 list, or an address that
  a direct DNS answer returned for a domestic name. Domestic names: `*.ir` plus the owner's list
  (suffix match; `www.`, scheme, path and port are stripped).
- **DNS split:** the router already answers the PC's DNS. Domestic names are resolved by the phone
  directly and their answers are learned as DIRECT; every other name is resolved **through the
  tunnel** (so foreign answers are not poisoned and do not leak to the local resolver).
- **No fallback to direct.** If the tunnel is down, tunnelled flows fail (TCP RST / ICMP
  unreachable). The PC never silently leaves with the domestic IP for a foreign site.
- **No Xray config selected** → everything DIRECT, i.e. the current behaviour.
- **Libraries:** Xray-core (MPL-2.0) and XTLS/libXray (MIT; share-link and subscription
  conversion, latency probes, geo files), built with gomobile into an AAR. **No v2rayNG code**
  (GPL-3.0 would force PdaNext to GPL).
- **Configs** are stored on the phone, encrypted with an Android Keystore key, never logged.
  The PC manages them over the existing link with new control requests (both sides change
  together, per CLAUDE.md).

## Known limits
- ICMP ping cannot go through Xray: pings to foreign hosts are not answered while a config is
  active (pings to the gateway and to DIRECT destinations still work).
- UDP through the tunnel depends on the outbound (vless/vmess with XUDP: yes).
- The Iranian IP list must be refreshed; it is generated in CI (the cloud dev container cannot
  reach RIPE) and shipped with the APK.
- APK size grows with the Go library (estimate 25-35 MB per ABI; split APKs per ABI).

## Slices
1. **Done:** `BypassPolicy` + `Ipv4Ranges` with unit tests (pure JVM, no wiring yet).
2. Router wiring behind an `Upstream` interface (`DirectUpstream` = today's sockets), DNS split,
   no-fallback rule; Linux netns e2e with a fake tunnel upstream.
3. Go module (`xraybridge`): start/stop an Xray instance from JSON, `Dial`, `DialUDP`, link import
   and latency test via libXray; gomobile AAR built in CI; Android `XrayUpstream`.
4. PC side: config list, switch, latency test, link import, bypass list editor; status JSON fields.
5. Iranian IP list generation in CI and update path.
