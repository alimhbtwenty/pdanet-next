#!/usr/bin/env bash
# Two-namespace test bed for tunnels/routers/relays. Run as root.
#
#   far side ("phone"/server) = root namespace, fake internet at 203.0.113.10 (+ name target.test)
#   client side ("PC")        = namespace "pc", linked by veth 10.200.0.1 <-> 10.200.0.2
#
# Usage:
#   SERVER_CMD="..."   command for the server under test (runs in the root namespace, background)
#   CLIENT_CMD="..."   command that brings up the client side inside "pc" (background), optional
#   CHECKS_FILE=...    bash file defining checks with: check "name" cmd args...   ($PC = "ip netns exec pc")
#   netns_harness.sh
#
# Built-in: an HTTP server and a UDP echo (port 9000) on 203.0.113.10, and the sanity check that the
# client cannot reach the target before the client side is up.
set -euo pipefail
unset http_proxy HTTP_PROXY https_proxy HTTPS_PROXY all_proxy ALL_PROXY no_proxy NO_PROXY JAVA_TOOL_OPTIONS

PASS=0; FAIL=0; PIDS=()
PC="ip netns exec pc"
TARGET=203.0.113.10

cleanup() {
  set +e
  for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done
  ip netns del pc 2>/dev/null
  ip link del veth-far 2>/dev/null
  ip addr del $TARGET/32 dev lo 2>/dev/null
  sed -i '/target\.test/d' /etc/hosts
  rm -rf /etc/netns/pc
  wait 2>/dev/null
}
trap cleanup EXIT

check() {
  local name="$1"; shift
  if out="$("$@" 2>&1)"; then echo "PASS  $name"; PASS=$((PASS + 1))
  else echo "FAIL  $name"; echo "$out" | tail -10 | sed 's/^/      /'; FAIL=$((FAIL + 1)); fi
}

# fake internet
ip addr add $TARGET/32 dev lo
echo "$TARGET target.test" >> /etc/hosts
python3 - <<'PY' &
import http.server, socketserver, socket, threading
def udp():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.bind(("203.0.113.10", 9000))
    while True:
        d, a = s.recvfrom(65535); s.sendto(b"echo:" + d, a)
threading.Thread(target=udp, daemon=True).start()
class H(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
socketserver.ThreadingTCPServer.allow_reuse_address = True
socketserver.ThreadingTCPServer(("203.0.113.10", 8000), H).serve_forever()
PY
PIDS+=($!)

# link
ip netns add pc
ip link add veth-far type veth peer name veth-pc
ip link set veth-pc netns pc
ip addr add 10.200.0.1/24 dev veth-far && ip link set veth-far up
$PC ip addr add 10.200.0.2/24 dev veth-pc && $PC ip link set veth-pc up && $PC ip link set lo up
mkdir -p /etc/netns/pc
echo "nameserver 9.9.9.9" > /etc/netns/pc/resolv.conf
grep -v target.test /etc/hosts > /etc/netns/pc/hosts
sleep 0.5

check "sanity: client cannot reach the target directly" bash -c "! $PC curl -sf --max-time 3 http://$TARGET:8000/ -o /dev/null"

if [[ -n "${SERVER_CMD:-}" ]]; then bash -c "$SERVER_CMD" & PIDS+=($!); sleep "${SERVER_WAIT:-2}"; fi
if [[ -n "${CLIENT_CMD:-}" ]]; then $PC bash -c "$CLIENT_CMD" & PIDS+=($!); sleep "${CLIENT_WAIT:-2}"; fi
if [[ -n "${CHECKS_FILE:-}" ]]; then source "$CHECKS_FILE"; fi

echo "--- $PASS passed, $FAIL failed ---"
[[ $FAIL -eq 0 ]]
