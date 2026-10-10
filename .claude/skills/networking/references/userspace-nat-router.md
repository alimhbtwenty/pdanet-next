# A userspace NAT router on the phone

When this matters: a remote host (PC) sends raw IP packets and the phone must act as its router
without root. Reference implementation: `core/src/main/kotlin/.../router/` in PdaNext.

## Shape
- Point-to-point network: gateway (phone) `192.168.42.1`, client (PC) `192.168.42.2/24`, ULA
  `fd42:42::1/2` for IPv6, MTU 9000 (large MTU means fewer packets through the userspace stack and
  avoids fragments).
- Packets **to the gateway**: answer ICMP echo locally, answer DNS (UDP 53), RST anything else.
- Packets to the client subnet, multicast, broadcast, link-local: drop.
- Everything else: NAT per protocol below. Drop IPv4 fragments and IPv6 extension headers (rare
  with a 9000 MTU).

## TCP termination (the hard part)
- Flow key = (pc ip, pc port, remote ip, remote port). On SYN: `connect()` from the phone (10 s
  timeout); success answers SYN-ACK (with our MSS), failure answers RST|ACK so the PC app fails fast.
- Keep sequence numbers as 32-bit **Int**: arithmetic wraps like TCP, and `(a - b) > 0` means
  "after". Convert with `toUInt().toLong()` when writing headers.
- The link is reliable and ordered, so a small TCP is enough: no window scaling (do not send the
  option, so the PC's window is unscaled too), no SACK, fixed 64 KB receive window that shrinks with
  bytes queued for the remote socket (back-pressure), window update once half the buffer frees up.
- Still implement retransmission: resend the oldest unacked segment after 1 s or on 3 duplicate ACKs;
  give up after ~12 tries. Resend SYN-ACK if the PC repeats its SYN.
- Half-close: PC FIN -> `shutdownOutput()` on the socket after queued data; socket EOF -> send FIN.
  Remove the flow when both FINs are done and ours is ACKed.
- Unknown non-SYN segments (e.g. after a reconnect) get a RST so apps retry.
- Two threads per flow (socket reader, socket writer); the packet-input thread must never block.
  Reader waits until the PC's window has room, reads at most min(MSS, window space).

## UDP
- One connected `DatagramSocket` per flow, receive thread builds reply packets with the remote as
  source. Idle timeout ~2 min (DNS port 20 s). Swallow per-packet send errors (ICMP port unreachable
  surfaces as exceptions on connected sockets) instead of closing the flow.
- Socket creation failure: answer ICMP destination unreachable so the app fails fast.

## ICMP
- Echo to the gateway: reply immediately (this is "ping the router").
- Echo elsewhere: unprivileged ping socket on Android (JVM fallback: `InetAddress.isReachable`),
  then build an echo reply with the original id/seq/payload. Cap concurrent pings.
- Phone -> PC ping: send an echo request from the gateway with a fixed id; match replies by seq.
- Traceroute (TTL-exceeded) and other IP protocols (GRE, raw ESP) cannot be done without root.

## DNS on the gateway
- Android 10+: `DnsResolver.rawQuery` on the default network (follows the VPN). Fallback: a tiny
  responder that parses one question and answers A/AAAA from `InetAddress.getAllByName`, NXDOMAIN on
  `UnknownHostException`, empty NOERROR for other types.
- If the PC must not use IPv6 around the phone (see windows-adapter-and-dns.md), answer AAAA **and
  HTTPS (type 65)** with "no data": HTTPS records carry `ipv6hint`.

## Checksums
- IPv4 header checksum; TCP/UDP/ICMPv6 use the pseudo-header (src, dst, protocol, length); ICMPv4
  does not. UDP checksum 0 means "none" (write 0xFFFF if the sum is 0).
- A Linux kernel peer silently drops bad checksums: an end-to-end test against a real TUN catches
  them, unit tests should still verify by recomputing to zero.
