#!/usr/bin/env python3
"""
Unit tests for tools/iran-ip-list/generate.py.
Runs offline with fixture.txt (no network).
"""

import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from generate import (
    ip_to_int,
    int_to_ip,
    interval_to_cidrs,
    parse_delegated_stats,
    format_output,
)


class TestIranIpListGenerate(unittest.TestCase):
    def setUp(self):
        self.fixture_path = os.path.join(os.path.dirname(__file__), "fixture.txt")

    def test_ip_conversion(self):
        self.assertEqual(ip_to_int("0.0.0.0"), 0)
        self.assertEqual(ip_to_int("255.255.255.255"), 0xFFFFFFFF)
        self.assertEqual(ip_to_int("192.168.1.1"), 0xC0A80101)
        self.assertEqual(int_to_ip(0xC0A80101), "192.168.1.1")
        with self.assertRaises(ValueError):
            ip_to_int("1.2.3")
        with self.assertRaises(ValueError):
            ip_to_int("1.2.3.256")

    def test_interval_to_cidrs(self):
        # Exact /24
        s = ip_to_int("192.168.1.0")
        e = ip_to_int("192.168.1.255")
        self.assertEqual(interval_to_cidrs(s, e), ["192.168.1.0/24"])

        # Single IP (/32)
        s = ip_to_int("10.0.0.1")
        self.assertEqual(interval_to_cidrs(s, s), ["10.0.0.1/32"])

        # Non-aligned 3 IPs: 10.0.0.1 - 10.0.0.3 -> /32 and /31
        s = ip_to_int("10.0.0.1")
        e = ip_to_int("10.0.0.3")
        self.assertEqual(interval_to_cidrs(s, e), ["10.0.0.1/32", "10.0.0.2/31"])

    def test_parse_fixture(self):
        with open(self.fixture_path, "r", encoding="utf-8") as f:
            cidrs = parse_delegated_stats(f)

        expected = [
            "2.144.0.0/13",
            "5.160.0.0/16",
            "91.98.0.0/32",
            "185.143.232.0/22",
        ]
        self.assertEqual(cidrs, expected)

    def test_format_output(self):
        cidrs = ["2.144.0.0/13", "5.160.0.0/16"]
        out = format_output(cidrs, source="test-source", date_str="2026-10-16")
        expected = (
            "# Source: test-source\n"
            "# Date: 2026-10-16\n"
            "# Count: 2\n"
            "2.144.0.0/13\n"
            "5.160.0.0/16\n"
        )
        self.assertEqual(out, expected)

    def test_empty_input(self):
        self.assertEqual(parse_delegated_stats([]), [])
        self.assertEqual(parse_delegated_stats(["# only comments"]), [])

    def test_min_ranges_threshold(self):
        with open(self.fixture_path, "r", encoding="utf-8") as f:
            cidrs = parse_delegated_stats(f)
        self.assertEqual(len(cidrs), 4)
        # Should satisfy min_ranges <= 4
        self.assertTrue(len(cidrs) >= 4)
        # Would fail if min_ranges > 4
        self.assertFalse(len(cidrs) >= 500)


if __name__ == "__main__":
    unittest.main()
