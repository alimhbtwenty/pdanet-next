# Ideas and open questions

Not built or not verified yet. Move items into a real reference once they are tested.

- **Wi-Fi / hotspot transport**: the router link is just TCP, so the PC app could connect to the
  phone's Wi-Fi address instead of `adb forward`. Needs: phone listening on the Wi-Fi interface,
  pairing (QR/PIN + challenge), a host route for the phone via Wi-Fi so the link does not enter the
  adapter, and preferably `LocalOnlyHotspot` (no carrier tethering check) *(unverified)*.
- **Kill switch**: keep a blocking default route (or firewall rule) when the link drops, so the PC
  never falls back to its own network.
- **Root mode**: with root, real kernel tethering plus `ip rule`/iptables into the VPN tun gives full
  IP (traceroute, GRE, ESP).
- **Devices without a client app**: only possible with root, a proxy, or a client app on that device
  (e.g. an Android VpnService client).
- **IPv6 end to end**: requires solving Windows source-address selection (see
  windows-adapter-and-dns.md) and the phone having IPv6.
