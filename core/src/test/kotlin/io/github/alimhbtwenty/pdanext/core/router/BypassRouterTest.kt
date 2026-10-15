package io.github.alimhbtwenty.pdanext.core.router

import io.github.alimhbtwenty.pdanext.core.RelayStats
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BypassRouterTest {
    private val pc = InetAddress.getByName("192.168.42.2")
    private val gw = InetAddress.getByName("192.168.42.1")
    private val domesticIp = InetAddress.getByName("5.200.1.1") // in 5.200.0.0/16
    private val foreignIp = InetAddress.getByName("1.1.1.1")

    class FakeUpstream(val name: String) : Upstream {
        val tcpRequests = CopyOnWriteArrayList<Pair<InetAddress, Int>>()
        val udpRequests = CopyOnWriteArrayList<Pair<InetAddress, Int>>()
        val dnsQueries = CopyOnWriteArrayList<ByteArray>()
        var failTcp = false
        var failUdp = false
        var dnsResolver: (ByteArray) -> ByteArray? = { null }

        override fun connectTcp(dest: InetAddress, port: Int, timeoutMs: Int): StreamConnection {
            tcpRequests += dest to port
            if (failTcp) throw IOException("$name connectTcp failed")
            return object : StreamConnection {
                override val input = ByteArrayInputStream(ByteArray(0))
                override val output = ByteArrayOutputStream()
                override fun shutdownOutput() {}
                override fun close() {}
            }
        }

        override fun openUdp(dest: InetAddress, port: Int): DatagramFlow {
            udpRequests += dest to port
            if (failUdp) throw IOException("$name openUdp failed")
            return object : DatagramFlow {
                override fun send(buf: ByteArray, offset: Int, length: Int) {}
                override fun receive(buf: ByteArray): Int {
                    Thread.sleep(50)
                    throw SocketTimeoutException()
                }
                override fun close() {}
            }
        }

        override fun resolveDns(query: ByteArray): ByteArray? {
            dnsQueries += query
            return dnsResolver(query)
        }
    }

    private fun buildDnsQuery(name: String, type: Int = MiniDns.TYPE_A): ByteArray {
        val q = ByteArrayOutputStream()
        q.write(byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        name.split('.').forEach {
            q.write(it.length)
            q.write(it.toByteArray())
        }
        q.write(0)
        q.write(byteArrayOf(0, type.toByte(), 0, 1))
        return q.toByteArray()
    }

    private fun testEnvironment(
        policyRanges: String = "5.200.0.0/16",
        policyDomains: List<String> = listOf("domestic.example"),
        currentTime: AtomicLong = AtomicLong(System.currentTimeMillis()),
    ): Triple<Router, FakeUpstream, FakeUpstream> {
        val out = LinkedBlockingQueue<ByteArray>()
        val direct = FakeUpstream("direct")
        val tunnel = FakeUpstream("tunnel")
        val policy = BypassPolicy(policyDomains, Ipv4Ranges.parse(policyRanges))
        val router = Router(
            config = RouterConfig(),
            sendToPc = { out.add(it) },
            direct = direct,
            tunnel = tunnel,
            policy = policy,
            stats = RelayStats(),
            clock = { currentTime.get() },
        )
        return Triple(router, direct, tunnel)
    }

    @Test
    fun `domestic IP goes direct and never touches tunnel`() {
        val (router, direct, tunnel) = testEnvironment()
        try {
            // TCP SYN to domestic IP
            val syn = PacketBuilder.tcp(pc, 45000, domesticIp, 80, 100, 0, TCP_SYN, 65535, mss = 1460)
            router.onPacket(syn, syn.size)
            Thread.sleep(100)

            assertEquals(1, direct.tcpRequests.size)
            assertEquals(domesticIp to 80, direct.tcpRequests[0])
            assertEquals(0, tunnel.tcpRequests.size)

            // UDP to domestic IP
            val udpData = "test".toByteArray()
            val udp = PacketBuilder.udp(pc, 45001, domesticIp, 9000, udpData, 0, udpData.size)
            router.onPacket(udp, udp.size)
            Thread.sleep(100)

            assertEquals(1, direct.udpRequests.size)
            assertEquals(domesticIp to 9000, direct.udpRequests[0])
            assertEquals(0, tunnel.udpRequests.size)
        } finally {
            router.shutdown()
        }
    }

    @Test
    fun `foreign IP goes tunnel and never touches direct`() {
        val (router, direct, tunnel) = testEnvironment()
        try {
            // TCP SYN to foreign IP
            val syn = PacketBuilder.tcp(pc, 45002, foreignIp, 443, 200, 0, TCP_SYN, 65535, mss = 1460)
            router.onPacket(syn, syn.size)
            Thread.sleep(100)

            assertEquals(1, tunnel.tcpRequests.size)
            assertEquals(foreignIp to 443, tunnel.tcpRequests[0])
            assertEquals(0, direct.tcpRequests.size)

            // UDP to foreign IP
            val udpData = "test-foreign".toByteArray()
            val udp = PacketBuilder.udp(pc, 45003, foreignIp, 53, udpData, 0, udpData.size)
            router.onPacket(udp, udp.size)
            Thread.sleep(100)

            assertEquals(1, tunnel.udpRequests.size)
            assertEquals(foreignIp to 53, tunnel.udpRequests[0])
            assertEquals(0, direct.udpRequests.size)
        } finally {
            router.shutdown()
        }
    }

    @Test
    fun `learned address goes direct until its TTL ends`() {
        val now = AtomicLong(1_000_000L)
        val (router, direct, tunnel) = testEnvironment(currentTime = now)
        try {
            val learnedIp = InetAddress.getByName("198.51.100.99")
            direct.dnsResolver = { query ->
                MiniDns.answer(query) { arrayOf(learnedIp) } // returns TTL 60
            }

            // 1. Resolve domestic domain via gateway DNS
            val dnsQuery = buildDnsQuery("domestic.example", MiniDns.TYPE_A)
            val udpDns = PacketBuilder.udp(pc, 40053, gw, 53, dnsQuery, 0, dnsQuery.size)
            router.onPacket(udpDns, udpDns.size)
            Thread.sleep(150)

            // Direct resolver was queried; policy learned learnedIp
            assertEquals(1, direct.dnsQueries.size)

            // 2. While within TTL (TTL is maxOf(60, 60) = 60s), flow goes DIRECT
            now.set(1_000_000L + 30_000L) // 30s elapsed
            val syn1 = PacketBuilder.tcp(pc, 50001, learnedIp, 443, 300, 0, TCP_SYN, 65535, mss = 1460)
            router.onPacket(syn1, syn1.size)
            Thread.sleep(100)

            assertEquals(1, direct.tcpRequests.size)
            assertEquals(learnedIp to 443, direct.tcpRequests[0])
            assertEquals(0, tunnel.tcpRequests.size)

            // 3. After TTL expires (65s elapsed), next flow to learnedIp goes to TUNNEL
            now.set(1_000_000L + 65_000L) // 65s elapsed, TTL expired
            val syn2 = PacketBuilder.tcp(pc, 50002, learnedIp, 443, 400, 0, TCP_SYN, 65535, mss = 1460)
            router.onPacket(syn2, syn2.size)
            Thread.sleep(100)

            assertEquals(1, tunnel.tcpRequests.size)
            assertEquals(learnedIp to 443, tunnel.tcpRequests[0])
        } finally {
            router.shutdown()
        }
    }

    @Test
    fun `tunnel failure gives RST and never attempts direct socket`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val direct = FakeUpstream("direct")
        val tunnel = FakeUpstream("tunnel")
        tunnel.failTcp = true
        tunnel.failUdp = true
        val policy = BypassPolicy(emptyList(), Ipv4Ranges.EMPTY)
        val router = Router(
            config = RouterConfig(),
            sendToPc = { out.add(it) },
            direct = direct,
            tunnel = tunnel,
            policy = policy,
            stats = RelayStats(),
        )
        try {
            // TCP: Tunnel fails -> answers with RST, never tries direct
            val syn = PacketBuilder.tcp(pc, 45010, foreignIp, 80, 500, 0, TCP_SYN, 65535, mss = 1460)
            router.onPacket(syn, syn.size)
            val replyBytes = out.poll(2, TimeUnit.SECONDS)
            assertNotNull(replyBytes, "expected RST reply to PC")
            val replyPkt = IpPacket.parse(replyBytes, replyBytes.size)!!
            assertEquals(PROTO_TCP, replyPkt.protocol)
            assertTrue(replyPkt.tcpFlags and TCP_RST != 0, "must send TCP RST")
            assertEquals(1, tunnel.tcpRequests.size)
            assertEquals(0, direct.tcpRequests.size) // NEVER fallback to direct

            // UDP: Tunnel fails -> answers with ICMP unreachable, never tries direct
            val udpData = "fail-test".toByteArray()
            val udp = PacketBuilder.udp(pc, 45011, foreignIp, 1234, udpData, 0, udpData.size)
            router.onPacket(udp, udp.size)
            val icmpBytes = out.poll(2, TimeUnit.SECONDS)
            assertNotNull(icmpBytes, "expected ICMP unreachable reply to PC")
            val icmpPkt = IpPacket.parse(icmpBytes, icmpBytes.size)!!
            assertEquals(PROTO_ICMP, icmpPkt.protocol)
            assertEquals(3, icmpPkt.icmpType) // Destination Unreachable
            assertEquals(1, tunnel.udpRequests.size)
            assertEquals(0, direct.udpRequests.size) // NEVER fallback to direct
        } finally {
            router.shutdown()
        }
    }

    @Test
    fun `DNS for dot ir name never reaches the tunnel`() {
        val (router, direct, tunnel) = testEnvironment()
        try {
            direct.dnsResolver = { query ->
                MiniDns.answer(query) { arrayOf(InetAddress.getByName("5.200.1.50")) }
            }
            val query = buildDnsQuery("varzesh3.ir", MiniDns.TYPE_A)
            val udp = PacketBuilder.udp(pc, 45020, gw, 53, query, 0, query.size)
            router.onPacket(udp, udp.size)
            Thread.sleep(150)

            assertEquals(1, direct.dnsQueries.size)
            assertEquals(0, tunnel.dnsQueries.size)
        } finally {
            router.shutdown()
        }
    }

    @Test
    fun `DNS for foreign name never reaches the direct resolver`() {
        val (router, direct, tunnel) = testEnvironment()
        try {
            tunnel.dnsResolver = { query ->
                MiniDns.answer(query) { arrayOf(InetAddress.getByName("142.250.190.46")) }
            }
            val query = buildDnsQuery("google.com", MiniDns.TYPE_A)
            val udp = PacketBuilder.udp(pc, 45021, gw, 53, query, 0, query.size)
            router.onPacket(udp, udp.size)
            Thread.sleep(150)

            assertEquals(1, tunnel.dnsQueries.size)
            assertEquals(0, direct.dnsQueries.size)
        } finally {
            router.shutdown()
        }
    }
}
