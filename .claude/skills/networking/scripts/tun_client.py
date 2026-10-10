#!/usr/bin/env python3
"""Linux stand-in for the Windows app in router mode.

Creates a TUN adapter, connects to the phone relay (the adb-forwarded port), performs the
"PDNR" handshake, configures the adapter from the addresses the phone sends, makes it the
default route, and pumps raw IP packets both ways. Same job as windows/src/RouterTunnel.cs.

    tun_client.py <relay-host> <relay-port> <interface-name>
"""
import fcntl
import ipaddress
import os
import socket
import struct
import subprocess
import sys
import threading

TUNSETIFF = 0x400454CA
IFF_TUN = 0x0001
IFF_NO_PI = 0x1000


def recv_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError("link closed")
        buf += chunk
    return bytes(buf)


def main():
    host, port, ifname = sys.argv[1], int(sys.argv[2]), sys.argv[3]

    sock = socket.create_connection((host, port))
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.sendall(b"PDNR\x01")
    hello = recv_exact(sock, 5 + 4 + 4 + 1 + 16 + 16 + 1 + 2)
    assert hello[:5] == b"PDNR\x01", hello[:5]
    gw4 = ipaddress.IPv4Address(hello[5:9])
    me4 = ipaddress.IPv4Address(hello[9:13])
    prefix4 = hello[13]
    gw6 = ipaddress.IPv6Address(hello[14:30])
    me6 = ipaddress.IPv6Address(hello[30:46])
    prefix6 = hello[46]
    mtu = struct.unpack(">H", hello[47:49])[0]

    tun = os.open("/dev/net/tun", os.O_RDWR)
    fcntl.ioctl(tun, TUNSETIFF, struct.pack("16sH", ifname.encode(), IFF_TUN | IFF_NO_PI))

    def ip(*args, check=True):
        subprocess.run(["ip", *args], check=check)

    ip("addr", "add", f"{me4}/{prefix4}", "dev", ifname)
    ip("link", "set", ifname, "mtu", str(mtu), "up")
    ip("route", "replace", "default", "via", str(gw4), "dev", ifname)
    if os.environ.get("NO_IPV6") != "1":
        ip("-6", "addr", "add", f"{me6}/{prefix6}", "dev", ifname, "nodad", check=False)
        ip("-6", "route", "replace", "default", "via", str(gw6), "dev", ifname, check=False)
    print(f"tun {ifname} up: {me4} gw {gw4} mtu {mtu}", flush=True)

    def link_to_tun():
        try:
            while True:
                (length,) = struct.unpack(">H", recv_exact(sock, 2))
                if length:
                    os.write(tun, recv_exact(sock, length))
        except Exception as e:  # noqa: BLE001
            print("link closed:", e, flush=True)
            os._exit(0)

    threading.Thread(target=link_to_tun, daemon=True).start()
    while True:
        packet = os.read(tun, 65535)
        sock.sendall(struct.pack(">H", len(packet)) + packet)


if __name__ == "__main__":
    main()
