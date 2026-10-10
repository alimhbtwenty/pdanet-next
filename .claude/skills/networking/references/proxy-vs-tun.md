# Choosing a design: proxy, SOCKS/UoT, TUN + fake-IP, raw-IP router

When this matters: deciding how to carry a PC's traffic through another machine (phone, server).

| Design | Covers | Visible to apps? | Notes |
| --- | --- | --- | --- |
| System HTTP proxy | apps that honor WinINet/WinHTTP | yes (proxy settings) | no UDP, no DNS, no ping; breaks the PC if left set after a crash |
| TUN + sing-box -> SOCKS5 with UDP-over-TCP | all TCP/UDP | adapter only, but fake-IP DNS is visible | no ICMP through SOCKS; needs a sing-box binary |
| Raw IP over a stream + userspace NAT on the far side | TCP, UDP, ICMP echo, DNS | looks like a normal router | most code (a small TCP stack); used by PdaNext 3 |
| Kernel forwarding (tethering) | everything | normal | bypasses an Android VPN; root needed to fix |

## sing-box facts (checked in v1.14.1 source)
- SOCKS outbound with `udp_over_tcp: { enabled: true, version: 2 }` carries UDP in a TCP stream:
  CONNECT to `sp.v2.udp-over-tcp.arpa`, then `isConnect(1) + SOCKS-format address`, then frames
  `[address unless isConnect] + u16 length + payload`. Frame address types: 0 IPv4, 1 IPv6, 2 domain
  (SOCKS uses 1/4/3). Source: `github.com/sagernet/sing/common/uot`.
- ICMP is only routed to outbounds that support it (direct, wireguard, tailscale, ...), not SOCKS.
- Fake-IP: DNS server `type: fakeip` (198.18.0.0/15, fc00::/18); connections to fake IPs are sent
  to the outbound as the domain name, so the far side resolves it.
- `strict_route` installs IPv6 policy rules too and fails on kernels without IPv6.
- Config migration warnings: 1.14 deprecates `independent_cache`; set `route.default_domain_resolver`.
- Build from source when release downloads are blocked:
  `GOTOOLCHAIN=auto go install -tags with_gvisor github.com/sagernet/sing-box/cmd/sing-box@vX`.

## Rule of thumb
If apps must not notice anything, terminate traffic remotely behind an adapter and return real DNS
answers. If ping matters, the transport must carry raw IP (or a protocol with ICMP), not SOCKS.
