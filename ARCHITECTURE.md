# Architecture

See `replica/recon.md` for how PdaNet+ works and `docs/HEALTH-CHECK.md` for what was wrong
with the previous version.

## Router mode (default)

1. The Windows app runs `adb -s <serial> forward tcp:28080 tcp:28080`, starts the phone app if needed
   (`am start ... --ez autostart true`), and connects to `127.0.0.1:28080`.
2. Link handshake (`core/.../router/RouterLink.kt`, `windows/src/RouterLinkProtocol.cs`):
   PC sends `"PDNR" 0x01`; the phone answers `"PDNR" 0x01` + gateway/client IPv4 + prefix +
   gateway/client IPv6 + prefix + MTU. Then both directions carry frames of `u16 length + IP packet`;
   length 0 is a keep-alive (every 15 s; 45 s of silence means the link is dead).
3. Windows creates the `PdaNet-Next` Wintun adapter, sets `192.168.42.2/24`, gateway and DNS
   `192.168.42.1`, interface metric 1 (default route), MTU 9000, and an NRPT rule `.` ->
   `192.168.42.1` so no DNS query goes to other networks. Two threads pump packets.
4. The phone's `Router` (userspace NAT):
   - **TCP** (`TcpSession`): the PC's SYN makes the phone `connect()`; on success it answers
     SYN-ACK, on failure RST. Data is relayed with a fixed 64 KB window (back-pressure), half-close,
     and timeout/3-dup-ACK retransmission. No window scaling or SACK: the USB link is reliable.
   - **UDP** (`UdpFlow`): one connected datagram socket per flow, idle timeout 2 min (DNS 20 s).
   - **ICMP echo**: to the gateway, answered locally; to anything else, sent with Android's
     unprivileged ping socket and answered back to the PC when the reply arrives.
   - **DNS** to the gateway: Android 10+ `DnsResolver.rawQuery` on the default network (the VPN if
     active); older Android: A/AAAA via the system resolver. AAAA/HTTPS answers are withheld.
   - Unknown TCP flows get RST, fragments are dropped (MTU 9000 avoids them).

## Browsers-only mode

The WinINet proxy points at `127.0.0.1:28080`, served by the phone's HTTP proxy. Previous proxy
values are saved in `HKCU\Software\PdaNet-Next\ProxyBackup` and restored on disconnect or on the next
start after a crash. (The same port also speaks SOCKS5 with UDP-over-TCP for manual use.)

## Failure handling

| Event | What happens |
| --- | --- |
| Cable unplugged | watchdog sees the device gone, removes the adapter and DNS rule, waits for the phone |
| Phone app killed | status fails 2x: forward re-created; 4x: teardown and "open the app" prompt |
| Link dies | pumps stop, adapter removed, automatic reconnect |
| Windows app crashes | the adapter disappears with the process; the NRPT rule and proxy are repaired on next start |
| Several phones | device picker; `-s <serial>` on every adb call |
| Phone in Doze | foreground service + partial wake lock + Wi-Fi low-latency lock |
