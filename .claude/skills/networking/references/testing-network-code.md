# Testing network code without the real devices

When this matters: proving a tunnel/router works end to end in CI or a container.

## Two-namespace test bed (see scripts/netns_harness.sh)
- "Far side" (phone) = root namespace: the server under test, plus a fake internet: add a TEST-NET
  address to `lo` (`ip addr add 203.0.113.10/32 dev lo`) and run HTTP/UDP echo servers on it, map a
  name in `/etc/hosts` (`target.test`).
- "Client" (PC) = `ip netns add pc`, joined by a veth pair `10.200.0.1/24 <-> 10.200.0.2/24`
  (stands in for the USB link). Give the client its own `/etc/netns/pc/resolv.conf` and a hosts file
  **without** the test name, so name resolution can only succeed through the tunnel.
- If the client needs a "default interface", point its default route at a gateway that does not
  exist (`via 10.200.0.254`): the route exists but nothing reaches the target directly.
- The client's TCP is the real Linux kernel: a strict peer for a userspace TCP implementation.

## Checks worth having
sanity (target unreachable before the tunnel), ping gateway, ping target, reverse ping, DNS A +
NXDOMAIN, TCP by IP and by name, 50 MB download with sha256, 5 MB upload, half-close
(server answers after client FIN), UDP echo incl. 8 KB datagrams, 50 parallel connections, refused
port gives RST quickly (`curl` exit 7), idle connection survives > any handshake timeout, and finally
the server's counters prove the bytes went through it.

## Pitfalls that produced false results
- No isolation: the client could reach the target directly, so early "passes" proved nothing.
- `for ...; do cmd & done; wait` always exits 0; `jobs -p` lists only running jobs. Collect PIDs
  and `wait $pid` each.
- Proxy env vars (`HTTP_PROXY`, `JAVA_TOOL_OPTIONS` proxy flags) leak into curl/Java in sandboxes:
  unset them in the test script.
- `pkill -f pattern` can match and kill the shell running it; kill by PID from `ps`.
- A stale server from an earlier build still bound to the port makes new tests talk to old code:
  check the bind error in the server log.
- Containers may lack IPv6 policy routing (`ip -6 rule` fails): detect and degrade, do not fail.
- Maven Central can answer 429 under load: retry with backoff.

## Unit level
- Build packets with your builder, parse them back, and verify checksums by recomputing to zero.
- Drive the router with in-memory packets for ICMP/DNS behaviour; test the PC-side protocol code
  against a live server process (it works on any OS).
