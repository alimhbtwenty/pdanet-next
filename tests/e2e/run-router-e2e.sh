#!/usr/bin/env bash
# End-to-end test of router mode on one Linux machine, no phone needed.
#
#   "phone"  = root network namespace: the Kotlin relay/router (core/) + test servers on 203.0.113.10
#   "PC"     = network namespace "pc": a TUN adapter fed by tests/e2e/tun_client.py (the Linux
#              stand-in for the Windows app), linked to the "phone" by a veth pair (= the ADB link)
#
# The "PC" has no route to 203.0.113.10 except the TUN, and cannot resolve target.test itself, so
# each passing check proves the packets went PC -> TUN -> router on the phone -> "internet".
# The PC's TCP is the real Linux kernel stack, which makes it a strict peer for the router's TCP.
#
# Needs root, iproute2, curl, python3, and a built relay: gradle -p core installDist
set -euo pipefail
unset http_proxy HTTP_PROXY https_proxy HTTPS_PROXY all_proxy ALL_PROXY no_proxy NO_PROXY JAVA_TOOL_OPTIONS

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
RELAY_BIN="$ROOT/core/build/install/core/bin/core"
PORT=28080
WORK="$(mktemp -d)"
PASS=0
FAIL=0

cleanup() {
  set +e
  [[ -n "${CLIENT_PID:-}" ]] && kill "$CLIENT_PID" 2>/dev/null
  [[ -n "${RELAY_PID:-}" ]] && kill "$RELAY_PID" 2>/dev/null
  [[ -n "${SRV_PID:-}" ]] && kill "$SRV_PID" 2>/dev/null
  ip netns del pc 2>/dev/null
  ip link del veth-phone 2>/dev/null
  ip addr del 203.0.113.10/32 dev lo 2>/dev/null
  sed -i '/target\.test/d' /etc/hosts
  rm -rf /etc/netns/pc
  wait 2>/dev/null
}
trap cleanup EXIT

check() {
  local name="$1"; shift
  if out="$("$@" 2>&1)"; then
    echo "PASS  $name"; PASS=$((PASS + 1))
  else
    echo "FAIL  $name"; echo "$out" | sed 's/^/      /' | tail -15; FAIL=$((FAIL + 1))
  fi
}

# --- "internet" seen from the phone ---
ip addr add 203.0.113.10/32 dev lo
echo "203.0.113.10 target.test" >> /etc/hosts
head -c 50000000 /dev/urandom > "$WORK/big.bin"
sha_big="$(sha256sum "$WORK/big.bin" | cut -d' ' -f1)"
python3 - "$WORK" <<'PY' &
import http.server, socketserver, socket, sys, threading, os
os.chdir(sys.argv[1])
def udp_echo():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("203.0.113.10", 9000))
    while True:
        data, addr = s.recvfrom(65535)
        s.sendto(b"echo:" + data, addr)
threading.Thread(target=udp_echo, daemon=True).start()
def slow_close():
    # Server that waits for the client to half-close, then answers (checks FIN handling).
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("203.0.113.10", 9001)); s.listen()
    while True:
        c, _ = s.accept()
        data = b""
        while True:
            chunk = c.recv(65536)
            if not chunk: break
            data += chunk
        c.sendall(b"got %d" % len(data)); c.close()
threading.Thread(target=slow_close, daemon=True).start()
class H(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); body = self.rfile.read(n)
        self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers()
        self.wfile.write(body)
socketserver.ThreadingTCPServer.allow_reuse_address = True
socketserver.ThreadingTCPServer.daemon_threads = True
socketserver.ThreadingTCPServer(("203.0.113.10", 8000), H).serve_forever()
def fake_tunnel():
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", 8002)); s.listen()
    while True:
        c, _ = s.accept()
        c.recv(1024)
        c.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\nfrom-tunnel")
        c.close()
threading.Thread(target=fake_tunnel, daemon=True).start()
PY
SRV_PID=$!

# --- the USB link: veth pair ---
ip netns add pc
ip link add veth-phone type veth peer name veth-pc
ip link set veth-pc netns pc
ip addr add 10.200.0.1/24 dev veth-phone
ip link set veth-phone up
ip netns exec pc ip addr add 10.200.0.2/24 dev veth-pc
ip netns exec pc ip link set veth-pc up
ip netns exec pc ip link set lo up
mkdir -p /etc/netns/pc
echo "nameserver 192.168.42.1" > /etc/netns/pc/resolv.conf
grep -v target.test /etc/hosts > /etc/netns/pc/hosts

PC="ip netns exec pc"
check "sanity: PC cannot reach the target before the link is up" bash -c "! $PC curl -sf --max-time 3 http://203.0.113.10:8000/ -o /dev/null"

# --- relay ("phone app") and TUN client ("PC app") ---
"$RELAY_BIN" --bind 10.200.0.1 --port "$PORT" --tunnel-port 8002 > "$WORK/relay.log" 2>&1 &
RELAY_PID=$!
for _ in $(seq 50); do (exec 3<>/dev/tcp/10.200.0.1/$PORT) 2>/dev/null && break; sleep 0.2; done
NO_IPV6=0
ip netns exec pc ip -6 rule list >/dev/null 2>&1 || NO_IPV6=1
NO_IPV6=$NO_IPV6 $PC python3 "$HERE/tun_client.py" 10.200.0.1 "$PORT" pdanet0 > "$WORK/client.log" 2>&1 &
CLIENT_PID=$!
for _ in $(seq 50); do grep -q "up:" "$WORK/client.log" 2>/dev/null && break; sleep 0.2; done
cat "$WORK/client.log"

echo "--- running checks ---"
check "ping the router (gateway answers itself)" $PC ping -c 3 -W 2 192.168.42.1
check "ping the internet through the phone" $PC ping -c 3 -W 3 203.0.113.10
check "phone pings the PC" bash -c "curl -s http://10.200.0.1:$PORT/ping-pc | grep -E '\"ms\":[0-9]+'"
check "DNS: the router answers A queries via the phone's resolver" bash -c "$PC python3 -c \"import socket; print(socket.gethostbyname('target.test'))\" | grep -qx 203.0.113.10"
check "DNS: NXDOMAIN for unknown names" bash -c "! $PC python3 -c \"import socket; socket.gethostbyname('nope.invalid')\""
check "TCP by IP (HTTP GET)" $PC curl -sf --max-time 10 http://203.0.113.10:8000/ -o /dev/null
check "TCP by name" $PC curl -sf --max-time 10 http://target.test:8000/ -o /dev/null
check "tunnel TCP: foreign IP routes through tunnel" bash -c "$PC curl -sf --max-time 10 http://203.0.113.20:8000/ | grep -q 'from-tunnel'"
check "tunnel DNS: foreign domain resolves via tunnel" bash -c "$PC python3 -c \"import socket; print(socket.gethostbyname('foreign.test'))\" | grep -qx 203.0.113.20"
check "tunnel TCP: foreign domain routes through tunnel" bash -c "$PC curl -sf --max-time 10 http://foreign.test:8000/ | grep -q 'from-tunnel'"
check "50 MB download, checksum intact" bash -c "[ \"\$($PC curl -sf --max-time 120 http://target.test:8000/big.bin | sha256sum | cut -d' ' -f1)\" = $sha_big ]"
check "5 MB upload (POST echo)" bash -c "head -c 5000000 /dev/urandom > $WORK/up.bin && $PC curl -sf --max-time 60 --data-binary @$WORK/up.bin -H 'Content-Type: application/octet-stream' http://target.test:8000/ | cmp - $WORK/up.bin"
check "half-close: server answers after the PC's FIN" bash -c "$PC python3 -c \"
import socket
s = socket.create_connection(('203.0.113.10', 9001), timeout=10)
s.sendall(b'x' * 300000); s.shutdown(socket.SHUT_WR)
data = b''
while True:
    c = s.recv(100)
    if not c: break
    data += c
assert data == b'got 300000', data
\""
check "UDP datagrams" $PC python3 -c "
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(5)
for i in range(50):
    s.sendto(b'hi%d' % i, ('203.0.113.10', 9000))
    data, _ = s.recvfrom(100)
    assert data == b'echo:hi%d' % i, data
"
check "large UDP datagram (8 KB)" $PC python3 -c "
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(5)
s.sendto(b'z' * 8000, ('203.0.113.10', 9000)); assert len(s.recvfrom(9000)[0]) == 8005
"
check "50 parallel TCP connections" bash -c "pids=(); for i in \$(seq 50); do $PC curl -sf --max-time 30 http://target.test:8000/ -o /dev/null & pids+=(\$!); done; ok=0; for p in \"\${pids[@]}\"; do wait \$p && ok=\$((ok+1)); done; echo ok=\$ok; [ \$ok = 50 ]"
check "closed port answers RST quickly (connection refused)" bash -c "$PC curl -s --max-time 5 http://203.0.113.10:9999/ ; [ \$? = 7 ]"
check "idle connection survives 20 s" $PC python3 -c "
import socket, time
s = socket.create_connection(('203.0.113.10', 9001), timeout=40)
time.sleep(20); s.sendall(b'abc'); s.shutdown(socket.SHUT_WR)
assert s.recv(100) == b'got 3'
"

echo "--- relay status endpoint ---"
status="$(curl -s "http://10.200.0.1:$PORT/status")"; echo "$status"
check "traffic was carried by the router (>= 50 MB down)" python3 -c "
import json, sys; s = json.loads(sys.argv[1]); assert s['routerMode'] and s['bytesDown'] >= 50_000_000, s
" "$status"
echo "--- $PASS passed, $FAIL failed ---"
if [[ $FAIL -gt 0 ]]; then
  echo "--- relay log ---"; tail -40 "$WORK/relay.log"
  echo "--- client log ---"; tail -20 "$WORK/client.log"
  exit 1
fi
