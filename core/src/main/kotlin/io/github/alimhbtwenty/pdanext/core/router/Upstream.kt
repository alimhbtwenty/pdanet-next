package io.github.alimhbtwenty.pdanext.core.router

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** A bidirectional byte stream (TCP). */
interface StreamConnection : Closeable {
    val input: InputStream
    val output: OutputStream
    fun shutdownOutput()
}

/** A flow of datagrams (UDP) to a single remote address and port. */
interface DatagramFlow : Closeable {
    fun send(buf: ByteArray, offset: Int, length: Int)
    /** Receives a datagram into [buf]; returns the length. Throws SocketTimeoutException on idle timeout. */
    fun receive(buf: ByteArray): Int
}

/**
 * An outbound transport for PC traffic: direct (the phone's own network) or a tunnel (e.g. Xray).
 */
interface Upstream {
    fun connectTcp(dest: InetAddress, port: Int, timeoutMs: Int): StreamConnection
    fun openUdp(dest: InetAddress, port: Int): DatagramFlow
    fun resolveDns(query: ByteArray): ByteArray?
}

/**
 * Today's direct socket behaviour: connects directly using [Socket] and [DatagramSocket],
 * and resolves DNS via the platform resolver.
 */
class DirectUpstream(val platform: RouterPlatform) : Upstream {

    override fun connectTcp(dest: InetAddress, port: Int, timeoutMs: Int): StreamConnection {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(dest, port), timeoutMs)
            s.tcpNoDelay = true
            s.keepAlive = true
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
        return SocketStreamConnection(s)
    }

    override fun openUdp(dest: InetAddress, port: Int): DatagramFlow {
        val s = DatagramSocket()
        try {
            s.connect(InetSocketAddress(dest, port))
            s.soTimeout = 1000
        } catch (e: Exception) {
            s.close()
            throw e
        }
        return SocketDatagramFlow(s)
    }

    override fun resolveDns(query: ByteArray): ByteArray? = platform.resolveDns(query)
}

class SocketStreamConnection(val socket: Socket) : StreamConnection {
    override val input: InputStream get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()
    override fun shutdownOutput() { runCatching { socket.shutdownOutput() } }
    override fun close() { socket.close() }
}

class SocketDatagramFlow(val socket: DatagramSocket) : DatagramFlow {
    private val packet = DatagramPacket(ByteArray(0), 0)

    override fun send(buf: ByteArray, offset: Int, length: Int) {
        socket.send(DatagramPacket(buf, offset, length))
    }

    override fun receive(buf: ByteArray): Int {
        packet.setData(buf)
        socket.receive(packet)
        return packet.length
    }

    override fun close() {
        socket.close()
    }
}
