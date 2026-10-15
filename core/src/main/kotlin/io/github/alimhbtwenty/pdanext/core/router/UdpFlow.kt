package io.github.alimhbtwenty.pdanext.core.router

import java.io.IOException
import java.net.SocketTimeoutException

/** One UDP flow from the PC (source port -> destination), mapped to a datagram flow. */
internal class UdpFlow(
    private val router: Router,
    val key: FlowKey,
    private val flow: DatagramFlow,
) {
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
            flow.send(buf, offset, length)
            router.stats.bytesUp.addAndGet(length.toLong())
        } catch (e: IOException) {
            // e.g. port unreachable reported on a connected socket; the PC app will retry or time out.
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(65535)
        try {
            while (!closed) {
                val len = try {
                    flow.receive(buf)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    if (closed) return
                    continue // ICMP errors on a connected socket surface here; keep the flow.
                }
                lastActive = System.currentTimeMillis()
                router.stats.bytesDown.addAndGet(len.toLong())
                router.send(PacketBuilder.udp(key.remote, key.remotePort, key.pc, key.pcPort, buf, 0, len))
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
        runCatching { flow.close() }
        router.udpClosed(key)
    }
}
