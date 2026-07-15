#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Standalone SSDP / DLNA discovery probe (stdlib only).

Sends an M-SEARCH to the SSDP multicast group (239.255.255.250:1900) from
every local IPv4 interface, listens for replies, and prints each discovered
device's USN / LOCATION / SERVER / ST plus the friendlyName fetched from its
device-description XML.

Purpose: isolate whether "can't find the TV" is a network/TV-side problem
(subnet mismatch, AP isolation, TV's DLNA off, firewall) or an Android/jUPnP
problem. If this script finds the TV from the PC, the network is fine and the
issue is in the app; if it also can't find the TV, it's the network or the TV.

Run:  python ssdp_probe.py [seconds]
"""

import os
import re
import socket
import struct
import sys
import time
import urllib.request

# Force UTF-8 console output so the Chinese messages don't mojibake on Windows.
if sys.platform == "win32":
    try:
        import ctypes
        ctypes.windll.kernel32.SetConsoleOutputCP(65001)
    except Exception:
        pass
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

SSDP_ADDR = "239.255.255.250"
SSDP_PORT = 1900
MX = 3
LISTEN_SEC = 10.0

# Targeted first (MediaRenderer), then a blanket ssdp:all to catch anything.
SEARCH_TARGETS = [
    "urn:schemas-upnp-org:device:MediaRenderer:1",
    "ssdp:all",
]


def local_ipv4s():
    """Non-loopback IPv4 addresses of this host (primary egress first)."""
    ips = []
    # Primary egress IP via a UDP connect (no packets actually sent).
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if not ip.startswith("127."):
            ips.append(ip)
    except OSError:
        pass
    # Any others the resolver knows about.
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except OSError:
        pass
    return ips


def msearch_message(st):
    return ("\r\n".join([
        "M-SEARCH * HTTP/1.1",
        "HOST: 239.255.255.250:1900",
        'MAN: "ssdp:discover"',
        "MX: %d" % MX,
        "ST: %s" % st,
        "",
        "",
    ])).encode("ascii")


def parse_headers(data):
    text = data.decode("utf-8", "replace")
    lines = text.split("\r\n")
    first = lines[0] if lines else ""
    hdrs = {}
    for ln in lines[1:]:
        if ":" in ln:
            k, v = ln.split(":", 1)
            hdrs[k.strip().upper()] = v.strip()
    return first, hdrs


def fetch_descriptor(location, timeout=2.5):
    """Return (deviceType, friendlyName) from the device description XML, or None."""
    try:
        req = urllib.request.Request(
            location, headers={"User-Agent": "SubCast-SSDP-probe/1.0"}
        )
        with urllib.request.urlopen(req, timeout=timeout) as r:
            xml = r.read().decode("utf-8", "replace")
    except Exception:
        return None
    dt = re.search(r"<deviceType>(.*?)</deviceType>", xml, re.S)
    fn = re.search(r"<friendlyName>(.*?)</friendlyName>", xml, re.S)
    return (
        dt.group(1).strip() if dt else "",
        fn.group(1).strip() if fn else "",
    )


def main():
    listen = float(sys.argv[1]) if len(sys.argv) > 1 else LISTEN_SEC
    ips = local_ipv4s()
    print("[i] 本机 IPv4: %s" % (", ".join(ips) if ips else "(未检测到)"))
    if not ips:
        print("[!] 没检测到局域网 IPv4,请确认 PC 已连 WiFi/有线网。")
        return

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", 0))
    port = sock.getsockname()[1]
    print("[i] 监听 UDP 0.0.0.0:%d" % port)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 4)

    # Join the multicast group on each interface so we also catch NOTIFY alive.
    for ip in ips:
        try:
            mreq = struct.pack("4s4s", socket.inet_aton(SSDP_ADDR), socket.inet_aton(ip))
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
        except OSError:
            pass

    # Send M-SEARCH from each interface, for each target.
    for ip in ips:
        try:
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(ip))
            for st in SEARCH_TARGETS:
                sock.sendto(msearch_message(st), (SSDP_ADDR, SSDP_PORT))
            print("[i] 已从 %s 发送 %d 条 M-SEARCH" % (ip, len(SEARCH_TARGETS)))
        except OSError as e:
            print("[!] 从 %s 发送失败: %s" % (ip, e))

    print("[i] 监听 %.0f 秒 ...\n" % listen)
    sock.settimeout(2.0)
    deadline = time.time() + listen
    seen = {}
    while time.time() < deadline:
        sock.settimeout(max(0.5, deadline - time.time()))
        try:
            data, addr = sock.recvfrom(65535)
        except socket.timeout:
            continue
        except OSError:
            continue
        first, hdrs = parse_headers(data)
        # Skip our own M-SEARCH echo if any.
        if first.startswith("M-SEARCH"):
            continue
        usn = hdrs.get("USN")
        loc = hdrs.get("LOCATION")
        key = usn or loc or (addr[0], addr[1])
        if key in seen:
            continue
        seen[key] = True

        desc = fetch_descriptor(loc) if loc else None
        is_renderer = bool(desc and "MediaRenderer" in desc[0]) if desc else False
        flag = "  <<< 电视/渲染器" if is_renderer else ""
        print("--- 来自 %s:%d 的响应%s ---" % (addr[0], addr[1], flag))
        print("  %s" % first.strip())
        if hdrs.get("SERVER"):    print("  SERVER        : %s" % hdrs["SERVER"])
        if hdrs.get("ST"):         print("  ST            : %s" % hdrs["ST"])
        if hdrs.get("NT"):         print("  NT            : %s" % hdrs["NT"])
        if usn:                    print("  USN           : %s" % usn)
        if loc:                    print("  LOCATION      : %s" % loc)
        if desc:
            print("  deviceType    : %s" % desc[0])
            print("  friendlyName : %s" % desc[1])
        elif loc:
            print("  friendlyName : (拉取设备描述失败,见下文排查)")
        print()

    sock.close()
    if not seen:
        print("[!] 没有收到任何 SSDP 响应。")
        print("    可能原因:")
        print("    1) PC 和电视不在同一子网(本机=%s,确认电视也是 192.168.110.x)" % (ips[0] if ips else "?"))
        print("    2) 路由器开了 AP 隔离 / 禁止组播")
        print("    3) 电视的 DLNA / 多屏互动 / 投屏接收 服务没开")
        print("    4) Windows 防火墙拦截了 Python 入站 UDP(首次运行可能弹窗,选允许)")
    else:
        print("[i] 共发现 %d 个不同设备(按 USN/LOCATION 去重)。" % len(seen))


if __name__ == "__main__":
    main()
