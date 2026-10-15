package io.github.alimhbtwenty.pdanext.core.router

import io.github.alimhbtwenty.pdanext.core.RelayStats
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RouterTest {
    private val pc = InetAddress.getByName("192.168.42.2")
    private val gw = InetAddress.getByName("192.168.42.1")

    @Test
    fun `built packets parse back with valid checksums`() {
        val data = "hello".toByteArray()
        for (bytes in listOf(
            PacketBuilder.tcp(gw, 80, pc, 5555, 0xFFFFFFF0L, 7, TCP_ACK or TCP_PSH, 1000, data, 0, data.size),
            PacketBuilder.udp(gw, 53, pc, 5555, data, 0, data.size),
            PacketBuilder.icmpEcho(gw, pc, true, 1, 2, data),
            PacketBuilder.icmpEcho(InetAddress.getByName("fd42:42::1"), InetAddress.getByName("fd42:42::2"), true, 1, 2, data),
            PacketBuilder.udp(InetAddress.getByName("fd42:42::1"), 53, InetAddress.getByName("fd42:42::2"), 9, data, 0, data.size),
        )) {
            val p = assertNotNull(IpPacket.parse(bytes, bytes.size))
            assertTrue(PacketBuilder.verify(p), "checksum of protocol ${p.protocol}")
        }
        val tcp = IpPacket.parse(PacketBuilder.tcp(gw, 80, pc, 5555, 0xFFFFFFF0L, 7, TCP_SYN, 1000, mss = 1460), 44)!!
        assertEquals(0xFFFFFFF0L, tcp.tcpSeq)
        assertEquals(1460, tcp.tcpMss())
    }

    @Test
    fun `fragments and truncated packets are ignored`() {
        val p = PacketBuilder.udp(gw, 53, pc, 5555, ByteArray(10), 0, 10)
        assertEquals(null, IpPacket.parse(p, 15))
        p[6] = 0x20 // more-fragments
        assertEquals(null, IpPacket.parse(p, p.size))
    }

    @Test
    fun `mini dns answers A, NXDOMAIN and empty for other types`() {
        fun query(name: String, type: Int): ByteArray {
            val q = java.io.ByteArrayOutputStream()
            q.write(byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            name.split('.').forEach { q.write(it.length); q.write(it.toByteArray()) }
            q.write(byteArrayOf(0, 0, type.toByte(), 0, 1))
            return q.toByteArray()
        }
        val a = MiniDns.answer(query("example.test", 1)) { arrayOf(InetAddress.getByName("203.0.113.7")) }!!
        assertEquals(0x1234, IpPacket.u16(a, 0))
        assertEquals(1, IpPacket.u16(a, 6)) // one answer
        assertTrue(a.takeLast(4) == listOf<Byte>(203.toByte(), 0, 113, 7))
        val nx = MiniDns.answer(query("nope.test", 1)) { throw java.net.UnknownHostException() }!!
        assertEquals(3, nx[3].toInt() and 0x0F)
        val mx = MiniDns.answer(query("example.test", 15)) { error("not called") }!!
        assertEquals(0, IpPacket.u16(mx, 6))
    }

    @Test
    fun `router answers pings to itself and DNS on the gateway`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val platform = object : RouterPlatform {
            override fun resolveDns(query: ByteArray) = MiniDns.answer(query) { arrayOf(InetAddress.getByName("203.0.113.9")) }
            override fun ping(destination: InetAddress, payload: ByteArray, timeoutMs: Int) = true
        }
        val router = Router(RouterConfig(), { out.add(it) }, platform, RelayStats())
        fun next(): IpPacket = out.poll(2, TimeUnit.SECONDS)!!.let { IpPacket.parse(it, it.size)!! }
        try {
            val echo = PacketBuilder.icmpEcho(pc, gw, false, 77, 5, "abc".toByteArray())
            router.onPacket(echo, echo.size)
            val reply = next()
            assertEquals(0, reply.icmpType)
            assertEquals(77, reply.icmpId)
            assertEquals(gw, reply.src)

            val query = byteArrayOf(0, 9, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 'a'.code.toByte(), 0, 0, 1, 0, 1)
            val udp = PacketBuilder.udp(pc, 40000, gw, 53, query, 0, query.size)
            router.onPacket(udp, udp.size)
            val answer = next()
            assertEquals(PROTO_UDP, answer.protocol)
            assertEquals(53, answer.srcPort)
            assertEquals(40000, answer.dstPort)
            assertEquals(1, IpPacket.u16(answer.buf, answer.udpDataOffset + 6)) // one answer record

            // AAAA is withheld so Windows never sends IPv6 out of another network.
            val aaaa = byteArrayOf(0, 10, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 'a'.code.toByte(), 0, 0, 28, 0, 1)
            val udp6 = PacketBuilder.udp(pc, 40001, gw, 53, aaaa, 0, aaaa.size)
            router.onPacket(udp6, udp6.size)
            val empty = next()
            assertEquals(0, IpPacket.u16(empty.buf, empty.udpDataOffset + 6))

            // Echo to the internet goes through the platform ping and comes back from the target.
            val target = InetAddress.getByName("203.0.113.50")
            val remoteEcho = PacketBuilder.icmpEcho(pc, target, false, 3, 4, "x".toByteArray())
            router.onPacket(remoteEcho, remoteEcho.size)
            val remoteReply = next()
            assertEquals(target, remoteReply.src)
            assertEquals(4, remoteReply.icmpSeq)
        } finally {
            router.shutdown()
        }
    }

    @Test
    fun `router with requireProtection blocks TCP SYN with RST and UDP with ICMP port unreachable when protection is down`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val platform = object : RouterPlatform {
            override fun resolveDns(query: ByteArray) = null
            override fun ping(destination: InetAddress, payload: ByteArray, timeoutMs: Int) = false
        }
        val config = RouterConfig(requireProtection = true)
        val router = Router(config, { out.add(it) }, platform, RelayStats(), isProtectedProvider = { false })
        fun next(): IpPacket = out.poll(2, TimeUnit.SECONDS)!!.let { IpPacket.parse(it, it.size)!! }
        try {
            val foreignDst = InetAddress.getByName("203.0.113.10")

            // 1. TCP SYN to foreign destination must get RST
            val syn = PacketBuilder.tcp(pc, 50000, foreignDst, 443, 1000L, 0, TCP_SYN, 65535)
            router.onPacket(syn, syn.size)
            val rst = next()
            assertEquals(PROTO_TCP, rst.protocol)
            assertEquals(443, rst.srcPort)
            assertEquals(50000, rst.dstPort)
            assertTrue(rst.tcpFlags and TCP_RST != 0, "RST flag must be set")
            assertTrue(rst.tcpFlags and TCP_ACK != 0, "ACK flag must be set for RST to SYN")
            assertEquals(1001L, rst.tcpAck, "ACK number must be seq + 1")

            // 2. UDP packet to foreign destination must get ICMP port unreachable (type 3 code 3)
            val udpData = "test-udp".toByteArray()
            val udp = PacketBuilder.udp(pc, 50001, foreignDst, 8080, udpData, 0, udpData.size)
            router.onPacket(udp, udp.size)
            val icmp = next()
            assertEquals(PROTO_ICMP, icmp.protocol)
            assertEquals(gw, icmp.src, "ICMP unreachable must come from gateway")
            assertEquals(pc, icmp.dst)
            assertEquals(3, icmp.icmpType, "ICMP type must be 3 (Destination Unreachable)")
            assertEquals(3, icmp.icmpCode, "ICMP code must be 3 (Port Unreachable)")

            // 3. Count is recorded and logged
            val logged = mutableListOf<String>()
            val loggingRouter = Router(config, { }, platform, RelayStats(), log = { logged += it }, isProtectedProvider = { false })
            loggingRouter.onPacket(syn, syn.size)
            loggingRouter.onPacket(udp, udp.size)
            val count = loggingRouter.flushBlockedLog()
            assertEquals(2, count)
            assertEquals(1, logged.size)
            assertTrue("kill switch: blocked 2 flow(s)" in logged[0], logged[0])
            loggingRouter.shutdown()
        } finally {
            router.shutdown()
        }
    }

    @Test
    fun `domestic DIRECT flows are allowed only in Xray mode when protection is down`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val platform = object : RouterPlatform {
            override fun resolveDns(query: ByteArray) = null
            override fun ping(destination: InetAddress, payload: ByteArray, timeoutMs: Int) = false
        }
        val domesticIp = InetAddress.getByName("185.143.232.1") // Iranian IP range in BypassPolicy
        val foreignIp = InetAddress.getByName("93.184.216.34")
        val irRanges = Ipv4Ranges.parse("185.143.232.0/22\n")
        val policy = BypassPolicy(emptyList(), irRanges)
        assertEquals(Route.DIRECT, policy.route(domesticIp))
        assertEquals(Route.TUNNEL, policy.route(foreignIp))

        // Case A: External VPN mode with protection DOWN -> Domestic DIRECT is NOT allowed (must get RST)
        val vpnRouter = Router(
            RouterConfig(requireProtection = true, protectionMode = ProtectionMode.EXTERNAL_VPN, bypassPolicy = policy),
            { out.add(it) }, platform, RelayStats(), isProtectedProvider = { false }
        )
        try {
            assertTrue(vpnRouter.shouldBlockFlow(domesticIp), "External VPN mode must block domestic when VPN is down")
            assertTrue(vpnRouter.shouldBlockFlow(foreignIp), "External VPN mode must block foreign when VPN is down")

            val synDomestic = PacketBuilder.tcp(pc, 50010, domesticIp, 443, 2000L, 0, TCP_SYN, 65535)
            vpnRouter.onPacket(synDomestic, synDomestic.size)
            val replyA = out.poll(2, TimeUnit.SECONDS)!!.let { IpPacket.parse(it, it.size)!! }
            assertTrue(replyA.tcpFlags and TCP_RST != 0, "External VPN mode must block domestic when VPN is down")
        } finally {
            vpnRouter.shutdown()
        }

        // Case B: Xray mode with protection DOWN -> Domestic DIRECT IS allowed (shouldBlockFlow == false)
        val xrayRouter = Router(
            RouterConfig(requireProtection = true, protectionMode = ProtectionMode.XRAY, bypassPolicy = policy),
            { out.add(it) }, platform, RelayStats(), isProtectedProvider = { false }
        )
        try {
            assertEquals(false, xrayRouter.shouldBlockFlow(domesticIp), "Domestic DIRECT in Xray mode must not be blocked")
            assertEquals(true, xrayRouter.shouldBlockFlow(foreignIp), "Foreign destination in Xray mode must be blocked")

            // Foreign destination in Xray mode MUST be blocked (RST)
            val synForeign = PacketBuilder.tcp(pc, 50012, foreignIp, 443, 2002L, 0, TCP_SYN, 65535)
            xrayRouter.onPacket(synForeign, synForeign.size)
            val replyForeign = out.poll(2, TimeUnit.SECONDS)!!.let { IpPacket.parse(it, it.size)!! }
            assertTrue(replyForeign.tcpFlags and TCP_RST != 0, "Foreign destination in Xray mode must be blocked when tunnel is down")

            // UDP to foreign destination gets ICMP port unreachable
            val udpForeign = PacketBuilder.udp(pc, 50013, foreignIp, 8080, "test".toByteArray(), 0, 4)
            xrayRouter.onPacket(udpForeign, udpForeign.size)
            val icmpForeign = out.poll(2, TimeUnit.SECONDS)!!.let { IpPacket.parse(it, it.size)!! }
            assertEquals(3, icmpForeign.icmpType, "Foreign UDP must get ICMP type 3")
            assertEquals(3, icmpForeign.icmpCode, "Foreign UDP must get ICMP code 3 (port unreachable)")

            // UDP to domestic destination in Xray mode is NOT blocked by kill switch (no ICMP port unreachable generated)
            val udpDomestic = PacketBuilder.udp(pc, 50014, domesticIp, 8080, "test".toByteArray(), 0, 4)
            xrayRouter.onPacket(udpDomestic, udpDomestic.size)
            val nextPacket = out.poll(200, TimeUnit.MILLISECONDS)
            assertTrue(nextPacket == null, "Domestic UDP in Xray mode must not receive ICMP port unreachable")
        } finally {
            xrayRouter.shutdown()
        }
    }
}
