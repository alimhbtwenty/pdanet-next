package io.github.alimhbtwenty.pdanext.core.router

import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BypassPolicyTest {
    private fun ip(s: String) = InetAddress.getByName(s)
    private fun ip4(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test
    fun `list entries reduce to host names`() {
        assertEquals("example.com", BypassPolicy.hostOf("https://www.Example.com:8443/a/b?c#d"))
        assertEquals("bank.example.ir", BypassPolicy.hostOf("bank.example.ir."))
        assertEquals("example.com", BypassPolicy.hostOf("*.example.com"))
        assertEquals("example.com", BypassPolicy.hostOf("http://user:pw@example.com/x"))
        assertEquals(null, BypassPolicy.hostOf("# comment"))
        assertEquals(null, BypassPolicy.hostOf("   "))
        assertEquals(null, BypassPolicy.hostOf("bad host!"))
    }

    @Test
    fun `ir names and listed domains with their subdomains are domestic`() {
        val p = BypassPolicy(listOf("https://www.digikala.com/", "aparat.com"))
        assertTrue(p.isDomesticName("shaparak.ir"))
        assertTrue(p.isDomesticName("WWW.SHAPARAK.IR."))
        assertTrue(p.isDomesticName("digikala.com"))
        assertTrue(p.isDomesticName("api.digikala.com"))
        assertTrue(p.isDomesticName("www.aparat.com"))
        assertFalse(p.isDomesticName("notdigikala.com"))
        assertFalse(p.isDomesticName("google.com"))
        assertFalse(p.isDomesticName("ir.example.com"))
        assertFalse(p.isDomesticName("com"))
    }

    @Test
    fun `ranges merge and match by binary search`() {
        val r = Ipv4Ranges.parse(
            """
            # Iran (sample)
            5.160.0.0/16
            5.160.128.0/17   # inside the one above
            2.144.0.0/14
            2.148.0.0/14     # adjacent: merges with the one above
            2a01:5ec0::/29   # IPv6: skipped
            185.143.232.7
            """.trimIndent(),
        )
        assertEquals(3, r.size)
        assertTrue(r.contains(ip4("5.160.0.0")))
        assertTrue(r.contains(ip4("5.160.255.255")))
        assertFalse(r.contains(ip4("5.161.0.0")))
        assertTrue(r.contains(ip4("2.151.255.255")))
        assertFalse(r.contains(ip4("2.152.0.0")))
        assertTrue(r.contains(ip4("185.143.232.7")))
        assertFalse(r.contains(ip4("185.143.232.8")))
        assertFalse(r.contains(ip4("8.8.8.8")))
        assertFalse(Ipv4Ranges.EMPTY.contains(ip4("5.160.0.1")))
    }

    @Test
    fun `bad ranges are rejected`() {
        assertFailsWith<IllegalArgumentException> { Ipv4Ranges.parse("5.160.0.0/33") }
        assertFailsWith<IllegalArgumentException> { Ipv4Ranges.parse("5.160.0/16") }
        assertFailsWith<IllegalArgumentException> { Ipv4Ranges.parse("300.1.1.1/8") }
    }

    @Test
    fun `routes follow ranges and learned answers until they expire`() {
        val p = BypassPolicy(ranges = Ipv4Ranges.parse("5.160.0.0/16"))
        assertEquals(Route.DIRECT, p.route(ip("5.160.1.2")))
        assertEquals(Route.TUNNEL, p.route(ip("104.16.1.1")))
        assertEquals(Route.TUNNEL, p.route(ip("2001:db8::1")))

        val now = 1_000_000L
        p.learn(listOf(ip("104.16.1.1"), ip("2001:db8::1")), ttlSeconds = 5, now = now)
        assertEquals(Route.DIRECT, p.route(ip("104.16.1.1"), now + 59_000))
        assertEquals(Route.DIRECT, p.route(ip("2001:db8::1"), now + 59_000))
        assertEquals(Route.TUNNEL, p.route(ip("104.16.1.1"), now + 61_000))
    }
}
