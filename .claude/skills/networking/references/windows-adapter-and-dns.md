# Windows: virtual adapter, routing and DNS

When this matters: a Windows app must send all of the PC's traffic somewhere (here: to a phone).

## Wintun
- `wintun.dll` (wintun.net, signed, WireGuard's driver). The adapter is a software device tied to the
  process: it disappears when the session ends **or the process dies**, so a crash cannot leave the
  PC routed into a dead adapter.
- P/Invoke surface: `WintunCreateAdapter(name, tunnelType, ref guid)`, `WintunOpenAdapter`,
  `WintunCloseAdapter`, `WintunStartSession(adapter, ringCapacity)`, `WintunGetReadWaitEvent`,
  `WintunReceivePacket(session, out size)` (NULL + error 259 = no packet yet, 38 = session ended),
  `WintunReleaseReceivePacket`, `WintunAllocateSendPacket`, `WintunSendPacket`, `WintunEndSession`.
- Load it from a subfolder with `LoadLibrary(fullPath)` before the first call. Use a fixed adapter
  GUID so Windows keeps one network profile. Close a stale adapter of the same name before creating.
- Join the pump threads before `WintunEndSession`: calling into an ended session is undefined.
- Build the app x64 to match the amd64 dll (runs emulated on ARM64).

## Addressing and routing (netsh)
```
netsh interface ipv4 set address name="X" source=static address=192.168.42.2 mask=255.255.255.0 gateway=192.168.42.1 gwmetric=1 store=active
netsh interface ipv4 set interface "X" metric=1 mtu=9000 store=active
netsh interface ipv4 set dnsservers name="X" source=static address=192.168.42.1 register=none validate=no
```
- Retry the first command for a few seconds: a fresh adapter takes a moment to appear.
- Interface metric 1 makes it the default route ahead of Wi-Fi/Ethernet; LAN subnets stay on their
  own connected routes. The link to the phone is USB (adb), so there is no routing loop to avoid.

## DNS leaks
- Windows sends queries to the DNS servers of **all** adapters (smart multi-homed resolution). Force
  everything to the phone with an NRPT rule:
  `Add-DnsClientNrptRule -Namespace '.' -NameServers '192.168.42.1' -Comment 'PdaNext'`.
- The NRPT rule survives a crash (unlike the adapter): remove it on disconnect, on the next start if a
  session marker says the last run did not close cleanly, and in the MSI uninstall.
- `ipconfig /flushdns` after adding/removing.

## IPv6 leaks
- With a ULA on the adapter and a global IPv6 on Wi-Fi, Windows source-address selection picks the
  Wi-Fi address for global destinations and sends IPv6 around the adapter. Simplest fix: give the PC
  no IPv6 answers (filter AAAA and HTTPS records in the router's DNS) and do not configure IPv6 on
  the adapter. IPv6 literals still leak (rare).

## Detectability
- A system proxy (WinINet `ProxyEnable/ProxyServer`, WinHTTP, `HTTP_PROXY`) is visible to apps and
  some (e.g. Antigravity) report it. An adapter with a gateway looks like an ordinary network.
- Fake-IP DNS (198.18.0.0/15 answers) is also visible to anything that inspects resolved addresses;
  real answers from the phone's resolver are not.

## Other Windows notes
- Admin is required for the adapter: `requireAdministrator` manifest. Start at logon without a UAC
  prompt each time with a scheduled task: `schtasks /Create /TN X /SC ONLOGON /RL HIGHEST /TR ...`.
- If you ever do touch the WinINet proxy: back up the previous values, restore exactly, and call
  `InternetSetOption(SETTINGS_CHANGED)` + `(REFRESH)`.
- Do not change the global TTL (`netsh int ipv4 set glob defaultcurhoplimit`): pointless when the phone
  re-creates flows, and it alters the PC for everything else.
