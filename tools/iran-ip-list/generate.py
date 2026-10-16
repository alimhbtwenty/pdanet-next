#!/usr/bin/env python3
"""
Generates the Iranian IPv4 CIDR list from RIPE NCC delegated stats.
Standard library only.
"""

import argparse
import datetime
import os
import sys
import urllib.request

DEFAULT_URL = "https://ftp.ripe.net/pub/stats/ripencc/delegated-ripencc-extended-latest"


def ip_to_int(ip_str: str) -> int:
    parts = ip_str.strip().split(".")
    if len(parts) != 4:
        raise ValueError(f"Invalid IPv4 address: {ip_str}")
    val = 0
    for p in parts:
        octet = int(p)
        if not (0 <= octet <= 255):
            raise ValueError(f"Invalid octet {octet} in {ip_str}")
        val = (val << 8) | octet
    return val


def int_to_ip(val: int) -> str:
    return f"{(val >> 24) & 0xFF}.{(val >> 16) & 0xFF}.{(val >> 8) & 0xFF}.{val & 0xFF}"


def interval_to_cidrs(start: int, end: int) -> list[str]:
    """Converts an integer IP interval [start, end] into minimal CIDR blocks."""
    cidrs = []
    cur = start
    while cur <= end:
        rem = end - cur + 1
        align = cur & -cur if cur != 0 else (1 << 32)
        max_p = 1 << (rem.bit_length() - 1)
        sz = min(align, max_p)
        prefix = 32 - (sz.bit_length() - 1)
        cidrs.append(f"{int_to_ip(cur)}/{prefix}")
        cur += sz
    return cidrs


def parse_delegated_stats(lines) -> list[str]:
    """
    Parses RIPE delegated stats records, keeping IPv4 records for country IR
    with status allocated or assigned. Merges overlapping and adjacent intervals,
    and returns a sorted list of CIDR strings.
    """
    intervals = []
    for line in lines:
        if isinstance(line, bytes):
            line = line.decode("ascii", errors="ignore")
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("|")
        if len(parts) < 7:
            continue
        _registry, cc, rtype, start, value, _date, status = parts[:7]
        if cc.upper() != "IR" or rtype.lower() != "ipv4":
            continue
        if status.lower() not in ("allocated", "assigned"):
            continue

        start_int = ip_to_int(start)
        count = int(value)
        if count <= 0:
            continue
        end_int = start_int + count - 1
        intervals.append((start_int, end_int))

    if not intervals:
        return []

    intervals.sort()
    merged = []
    for s, e in intervals:
        if not merged:
            merged.append((s, e))
        else:
            ps, pe = merged[-1]
            if s <= pe + 1:
                merged[-1] = (ps, max(pe, e))
            else:
                merged.append((s, e))

    cidrs = []
    for s, e in merged:
        cidrs.extend(interval_to_cidrs(s, e))

    return cidrs


def format_output(cidrs: list[str], source: str, date_str: str) -> str:
    lines = [
        f"# Source: {source}",
        f"# Date: {date_str}",
        f"# Count: {len(cidrs)}",
    ]
    lines.extend(cidrs)
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description="Generate Iranian IPv4 CIDR list from RIPE delegated stats")
    parser.add_argument("-i", "--input", default=DEFAULT_URL, help=f"Input file path or URL (default: {DEFAULT_URL})")
    parser.add_argument("-o", "--output", default=None, help="Output file path (default: stdout)")
    parser.add_argument("--min-ranges", type=int, default=0, help="Fail if generated ranges count is below this number")
    parser.add_argument("--date", default=None, help="Override date string in header comment (YYYY-MM-DD)")
    args = parser.parse_args()

    date_str = args.date or datetime.date.today().isoformat()
    source_desc = args.input

    if args.input.startswith("http://") or args.input.startswith("https://"):
        req = urllib.request.Request(args.input, headers={"User-Agent": "PdaNext/1.0"})
        with urllib.request.urlopen(req, timeout=60) as resp:
            cidrs = parse_delegated_stats(resp)
    else:
        with open(args.input, "r", encoding="utf-8") as f:
            cidrs = parse_delegated_stats(f)

    if args.min_ranges > 0 and len(cidrs) < args.min_ranges:
        sys.stderr.write(
            f"Error: generated {len(cidrs)} ranges, which is fewer than required minimum {args.min_ranges}\n"
        )
        sys.exit(1)

    output_text = format_output(cidrs, source_desc, date_str)

    if args.output:
        out_dir = os.path.dirname(args.output)
        if out_dir:
            os.makedirs(out_dir, exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(output_text)
        print(f"Wrote {len(cidrs)} Iranian IPv4 ranges to {args.output}", file=sys.stderr)
    else:
        sys.stdout.write(output_text)


if __name__ == "__main__":
    main()
