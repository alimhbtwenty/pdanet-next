package io.github.alimhbtwenty.pdanext.core.router

import io.github.alimhbtwenty.pdanext.core.RelayStats
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Addresses of the point-to-point network between the phone ("router") and the PC. */
data class RouterConfig(
    val gateway4: InetAddress = InetAddress.getByName("192.168.42.1"),
    val client4: InetAddress = InetAddress.getByName("192.168.42.2"),
    val prefix4: Int = 24,
    val gateway6: InetAddress = InetAddress.getByName("fd42:42::1"),
    val client6: InetAddress = InetAddress.getByName("fd42:42::2"),
    val prefix6: Int = 64,
    val mtu: Int = 9000,
    /**
     * Answer AAAA/HTTPS queries with "no data". Windows would send IPv6 out of its other network
     * (e.g. Wi-Fi) by source-address rules and bypass the phone; IPv4-only DNS keeps every flow here.
     */
    val ipv4OnlyDns: Boolean = true,
)

/** What the router needs from the platform it runs on. */
interface RouterPlatform {
    /** Answers a raw DNS query using the phone's resolver (and therefore its VPN, if any). */
    fun resolveDns(query: ByteArray): ByteArray?

    /** Sends an ICMP echo from the phone; true if a reply came back in time. */
    fun ping(destination: InetAddress, payload: ByteArray, timeoutMs: Int): Boolean
}

data class FlowKey(val pc: InetAddress, val pcPort: Int, val remote: InetAddress, val remotePort: Int) {
    override fun toString() = "${pc.hostAddress}:$pcPort -> ${remote.hostAddress}:$remotePort"
}

/**
 * A userspace NAT router. The PC sends raw IP packets over the USB link; the router
 * terminates them and re-creates each flow with ordinary sockets on the phone, so every flow
 * follows the phone's routing (mobile data, Wi-Fi, or the active VPN). Replies are turned
 * back into IP packets for the PC.
 *
 * TCP is terminated (see [TcpSession]); UDP flows map 1:1 to datagram sockets; ICMP echo uses
 * the platform's unprivileged ping; DNS sent to the gateway is answered by the phone's resolver.
 */
class Router(
    val config: RouterConfig,
    private val sendToPc: (ByteArray) -> Unit,
    private val platform: RouterPlatform,
    val stats: RelayStats,
    private val log: (String) -> Unit = {},
) {
    internal val executor: ExecutorService = Executors.newCachedThreadPool(daemon("router"))
    internal val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemon("router-timer"))
    internal val tcp = ConcurrentHashMap<FlowKey, TcpSession>()
    private val udp = ConcurrentHashMap<FlowKey, UdpFlow>()
    private val pendingPings = ConcurrentHashMap<Int, CountDownLatch>()
    private val pingSeq = AtomicInteger()
    private val activePings = AtomicInteger()
    @Volatile private var closed = false

    val tcpMss4 get() = config.mtu - 40
    val tcpMss6 get() = config.mtu - 60

    init {
        timer.scheduleWithFixedDelay({ runCatching { tick() } }, 200, 200, TimeUnit.MILLISECONDS)
    }

    fun onPacket(buf: ByteArray, length: Int) {
        if (closed) return
        val p = IpPacket.parse(buf, length) ?: return
        if (!isFromClient(p)) return
        val dst = p.dst
        if (isLocal(dst)) {
            handleGatewayPacket(p)
            return
        }
        if (dst.isMulticastAddress || dst.isAnyLocalAddress || dst.isLinkLocalAddress || isBroadcast(dst) || isOwnSubnet(dst)) return
        when (p.protocol) {
            PROTO_TCP -> handleTcp(p)
            PROTO_UDP -> handleUdp(p)
            PROTO_ICMP, PROTO_ICMPV6 -> handleIcmp(p)
        }
    }

    // --- gateway ("router") itself ---

    private fun handleGatewayPacket(p: IpPacket) {
        when {
            isEchoRequest(p) -> send(PacketBuilder.icmpEcho(p.dst, p.src, true, p.icmpId, p.icmpSeq, p.copyOf(p.icmpDataOffset, p.icmpDataLength)))
            isEchoReply(p) && p.icmpId == PING_ID -> pendingPings.remove(p.icmpSeq)?.countDown()
            p.protocol == PROTO_UDP && p.dstPort == 53 -> {
                val query = p.copyOf(p.udpDataOffset, p.udpDataLength)
                val gateway = p.dst
                val pc = p.src
                val pcPort = p.srcPort
                executor.execute {
                    val type = MiniDns.parseQuestion(query)?.type
                    val answer = if (config.ipv4OnlyDns && (type == MiniDns.TYPE_AAAA || type == MiniDns.TYPE_HTTPS)) {
                        MiniDns.noData(query)
                    } else {
                        runCatching { platform.resolveDns(query) }.getOrNull() ?: MiniDns.serverFailure(query)
                    }
                    if (answer != null) send(PacketBuilder.udp(gateway, 53, pc, pcPort, answer, 0, answer.size))
                }
            }
            p.protocol == PROTO_TCP && p.tcpFlags and TCP_RST == 0 -> sendRstFor(p)
        }
    }

    /** Pings the PC from the phone; round-trip time in ms, or null. */
    fun pingClient(v6: Boolean = false, timeoutMs: Long = 2000): Long? {
        val seq = pingSeq.incrementAndGet() and 0xFFFF
        val latch = CountDownLatch(1)
        pendingPings[seq] = latch
        val (src, dst) = if (v6) config.gateway6 to config.client6 else config.gateway4 to config.client4
        val start = System.nanoTime()
        send(PacketBuilder.icmpEcho(src, dst, false, PING_ID, seq, "pdanext".toByteArray()))
        val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        pendingPings.remove(seq)
        return if (ok) (System.nanoTime() - start) / 1_000_000 else null
    }

    // --- ICMP echo to the internet ---

    private fun handleIcmp(p: IpPacket) {
        if (!isEchoRequest(p)) return
        if (activePings.get() >= MAX_PINGS) return
        val payload = p.copyOf(p.icmpDataOffset, p.icmpDataLength)
        val id = p.icmpId
        val seq = p.icmpSeq
        val remote = p.dst
        val pc = p.src
        activePings.incrementAndGet()
        executor.execute {
            try {
                if (platform.ping(remote, payload, 4000)) {
                    send(PacketBuilder.icmpEcho(remote, pc, true, id, seq, payload, ttl = 56))
                }
            } catch (e: Exception) {
                log("ping ${remote.hostAddress} failed: ${e.message}")
            } finally {
                activePings.decrementAndGet()
            }
        }
    }

    // --- UDP ---

    private fun handleUdp(p: IpPacket) {
        val key = FlowKey(p.src, p.srcPort, p.dst, p.dstPort)
        var flow = udp[key]
        if (flow == null || flow.closed) {
            flow = try {
                UdpFlow(this, key)
            } catch (e: Exception) {
                log("udp $key: ${e.message}")
                send(PacketBuilder.unreachable(gatewayFor(p), p))
                return
            }
            udp[key] = flow
            stats.activeUdp.incrementAndGet()
        }
        flow.send(p.buf, p.udpDataOffset, p.udpDataLength)
    }

    internal fun udpClosed(key: FlowKey) {
        if (udp.remove(key) != null) stats.activeUdp.decrementAndGet()
    }

    // --- TCP ---

    private fun handleTcp(p: IpPacket) {
        val key = FlowKey(p.src, p.srcPort, p.dst, p.dstPort)
        val flags = p.tcpFlags
        val session = tcp[key]
        if (session != null) {
            session.onPacket(p)
            return
        }
        if (flags and TCP_SYN != 0 && flags and TCP_ACK == 0) {
            val s = TcpSession(this, key, p)
            if (tcp.putIfAbsent(key, s) == null) {
                stats.activeTcp.incrementAndGet()
                stats.totalConnections.incrementAndGet()
                s.start()
            }
        } else if (flags and TCP_RST == 0) {
            // A flow we do not know (e.g. from before a reconnect): reset it so the app retries.
            sendRstFor(p)
        }
    }

    internal fun tcpClosed(key: FlowKey) {
        if (tcp.remove(key) != null) stats.activeTcp.decrementAndGet()
    }

    private fun sendRstFor(p: IpPacket) {
        val flags = p.tcpFlags
        val segLen = p.tcpDataLength + (if (flags and TCP_SYN != 0) 1 else 0) + (if (flags and TCP_FIN != 0) 1 else 0)
        val pkt = if (flags and TCP_ACK != 0) {
            PacketBuilder.tcp(p.dst, p.dstPort, p.src, p.srcPort, p.tcpAck, 0, TCP_RST, 0)
        } else {
            PacketBuilder.tcp(p.dst, p.dstPort, p.src, p.srcPort, 0, (p.tcpSeq + segLen) and 0xFFFFFFFFL, TCP_RST or TCP_ACK, 0)
        }
        send(pkt)
    }

    // --- plumbing ---

    internal fun send(packet: ByteArray) {
        if (!closed) sendToPc(packet)
    }

    internal fun log(message: String) = log.invoke(message)

    private fun tick() {
        val now = System.currentTimeMillis()
        tcp.values.forEach { it.onTimer(now) }
        udp.values.forEach { it.onTimer(now) }
    }

    fun shutdown() {
        closed = true
        tcp.values.toList().forEach { it.abort(sendReset = false) }
        udp.values.toList().forEach { it.close() }
        pendingPings.values.forEach { it.countDown() }
        timer.shutdownNow()
        executor.shutdownNow()
    }

    internal fun gatewayFor(p: IpPacket) = if (p.isV6) config.gateway6 else config.gateway4

    private fun isFromClient(p: IpPacket) = p.src == config.client4 || p.src == config.client6 || (p.isV6 && p.src.isLinkLocalAddress)
    private fun isLocal(a: InetAddress) = a == config.gateway4 || a == config.gateway6
    private fun isBroadcast(a: InetAddress) = a is Inet4Address && (a.address.all { it == 0xFF.toByte() } || isSubnetBroadcast(a))
    private fun isSubnetBroadcast(a: InetAddress) = inSubnet(a, config.gateway4, config.prefix4) && a.address.last() == 0xFF.toByte()
    private fun isOwnSubnet(a: InetAddress) =
        if (a is Inet4Address) inSubnet(a, config.gateway4, config.prefix4) else inSubnet(a, config.gateway6, config.prefix6)

    private fun isEchoRequest(p: IpPacket) =
        (p.protocol == PROTO_ICMP && p.icmpType == 8) || (p.protocol == PROTO_ICMPV6 && p.icmpType == 128)

    private fun isEchoReply(p: IpPacket) =
        (p.protocol == PROTO_ICMP && p.icmpType == 0) || (p.protocol == PROTO_ICMPV6 && p.icmpType == 129)

    companion object {
        const val PING_ID = 0x5044
        const val MAX_PINGS = 64

        fun inSubnet(a: InetAddress, net: InetAddress, prefix: Int): Boolean {
            val x = a.address
            val y = net.address
            if (x.size != y.size) return false
            var bits = prefix
            for (i in x.indices) {
                if (bits <= 0) return true
                val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
                if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
                bits -= 8
            }
            return true
        }

        internal fun daemon(prefix: String) = io.github.alimhbtwenty.pdanext.core.RelayServer.daemonThreads(prefix)
    }
}
