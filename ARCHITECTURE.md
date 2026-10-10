# Architecture

See `replica/recon.md` for how PdaNet+ works and why this design was chosen, and
`docs/HEALTH-CHECK.md` for what was wrong with the previous version.

## Data path

1. Windows app runs `adb -s <serial> forward tcp:28080 tcp:28080`: the PC's `127.0.0.1:28080`
   now reaches the phone's `127.0.0.1:28080` over USB.
2. **Whole PC mode**: sing-box creates the `PdaNet-Next` Wintun adapter with `auto_route` and
   `strict_route`. Its only outbound is SOCKS5 to `127.0.0.1:28080` with UDP-over-TCP v2.
   DNS queries are hijacked and A/AAAA answered with fake IPs (198.18.0.0/15, fc00::/18); when an app
   connects to a fake IP, sing-box sends the *domain name* to the relay, so the phone resolves it
   with its own DNS (or its VPN's). LAN ranges stay on the PC's own network by default.
   **Browsers only mode**: the WinINet proxy is set to `127.0.0.1:28080` (HTTP proxy); the previous
   values are backed up in `HKCU\Software\PdaNet-Next\ProxyBackup` and restored on disconnect or on
   the next start after a crash.
3. The relay (`core/`) accepts on loopback only. First byte `0x05` means SOCKS5, anything else is
   HTTP (proxy requests or `GET /status`).
   - SOCKS5 CONNECT: opens a socket from the phone and pipes both ways with half-close and no idle
     timeout.
   - CONNECT to `sp.v2.udp-over-tcp.arpa`: UDP-over-TCP session. Request = `isConnect` byte + SOCKS
     address; then frames of `[address] + u16 length + payload` both ways (address omitted in
     connect mode). Address types in frames: 0 IPv4, 1 IPv6, 2 domain.
   - `GET /status`: JSON counters plus the phone's uplink and VPN state, polled by the Windows app.

## Failure handling

| Event | What happens |
| --- | --- |
| Cable unplugged | Windows watchdog sees the device gone, stops the adapter / restores proxy, waits for the phone |
| Phone app killed | status fails 2x: forward re-created; 4x: teardown and "open the app" prompt |
| sing-box exits | teardown, then automatic reconnect |
| Windows app crashes | sing-box is in a kill-on-close job object, so it dies with the app; proxy settings and the session marker are repaired on next start |
| Several phones | device picker; `-s <serial>` on every adb call |
| Phone in Doze | foreground service + partial wake lock + Wi-Fi low-latency lock |
