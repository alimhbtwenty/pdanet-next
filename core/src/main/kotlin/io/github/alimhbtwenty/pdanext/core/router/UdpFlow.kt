package io.github.alimhbtwenty.pdanext.core.router

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** One UDP flow from the PC (source port -> destination), mapped to a connected datagram socket. */
internal class UdpFlow(private val router: Router, private val key: FlowKey) {
    private val socket = DatagramSocket().apply { connect(InetSocketAddress(key.remote, key.remotePort)) }
    private val idleMs = if (key.remotePort == 53) 20_000L else 120_000L
    @Volatile var closed = false
        private set
    @Volatile private var lastActive = System.currentTimeMillis()

    init {
        router.executor.execute(::receiveLoop)
    }

    fun send(buf: ByteArray, offset: Int, length: Int) {
        lastActive = System.currentTimeMillis()
        try {
            socket.send(DatagramPacket(buf, offset, length))
            router.stats.bytesUp.addAndGet(length.toLong())
        } catch (e: IOException) {
            // e.g. port unreachable reported on a connected socket; the PC app will retry or time out.
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(65535)
        val packet = DatagramPacket(buf, buf.size)
        try {
            socket.soTimeout = 1000
            while (!closed) {
                try {
                    packet.setData(buf)
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    if (closed) return
                    continue // ICMP errors on a connected socket surface here; keep the flow.
                }
                lastActive = System.currentTimeMillis()
                router.stats.bytesDown.addAndGet(packet.length.toLong())
                router.send(PacketBuilder.udp(key.remote, key.remotePort, key.pc, key.pcPort, buf, 0, packet.length))
            }
        } finally {
            close()
        }
    }

    fun onTimer(now: Long) {
        if (now - lastActive > idleMs) close()
    }

    fun close() {
        if (closed) return
        closed = true
        socket.close()
        router.udpClosed(key)
    }
}
