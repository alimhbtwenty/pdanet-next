package io.github.alimhbtwenty.pdanext

import io.github.alimhbtwenty.pdanext.core.router.DatagramFlow
import io.github.alimhbtwenty.pdanext.core.router.StreamConnection
import io.github.alimhbtwenty.pdanext.core.router.Upstream
import io.github.alimhbtwenty.pdanext.core.xray.XrayEngine
import io.github.alimhbtwenty.pdanext.core.xray.XrayProfile
import org.json.JSONArray
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.SocketTimeoutException
import xraybridge.Xraybridge

/**
 * [XrayEngine] on top of the `xraybridge` Go module (gomobile AAR): Xray runs in this process with
 * outbounds only. Configs never reach a log or an exception message.
 */
class XrayEngineImpl : XrayEngine {
    private val lock = Any()

    override fun start(configJson: String) {
        synchronized(lock) {
            try {
                Xraybridge.start(configJson)
            } catch (e: Exception) {
                // The bridge never puts the config in its errors; keep only its message.
                throw IllegalArgumentException(e.message ?: "invalid Xray config")
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            Xraybridge.stop()
        }
    }

    override val isRunning: Boolean get() = Xraybridge.isRunning()

    override val tunnel: Upstream = object : Upstream {
        override fun connectTcp(dest: InetAddress, port: Int, timeoutMs: Int): StreamConnection {
            val conn = try {
                Xraybridge.dialTCP(dest.hostAddress, port.toLong(), timeoutMs.toLong())
            } catch (e: Exception) {
                throw IOException("tunnel: ${e.message}")
            }
            return BridgeStream(conn)
        }

        override fun openUdp(dest: InetAddress, port: Int): DatagramFlow {
            val pc = try {
                Xraybridge.dialUDP(dest.hostAddress, port.toLong())
            } catch (e: Exception) {
                throw IOException("tunnel: ${e.message}")
            }
            return BridgeDatagram(pc)
        }

        /** Sends the raw DNS query through the tunnel to a public resolver. */
        override fun resolveDns(query: ByteArray): ByteArray? {
            val pc = try {
                Xraybridge.dialUDP(TUNNEL_DNS, 53)
            } catch (e: Exception) {
                return null
            }
            try {
                pc.send(query)
                return pc.receive(DNS_TIMEOUT_MS.toLong())
            } catch (e: Exception) {
                return null
            } finally {
                runCatching { pc.close() }
            }
        }
    }

    override fun importLinks(text: String): List<XrayProfile> {
        val json = try {
            Xraybridge.importLinks(text)
        } catch (e: Exception) {
            return emptyList()
        }
        val array = JSONArray(json)
        return (0 until array.length()).map {
            val o = array.getJSONObject(it)
            XrayProfile(o.getString("name"), o.getString("config"))
        }
    }

    override fun measureDelay(configJson: String, url: String, timeoutMs: Int): Long? =
        try {
            Xraybridge.measureDelay(configJson, url, timeoutMs.toLong())
        } catch (e: Exception) {
            null
        }

    private class BridgeStream(private val conn: xraybridge.Conn) : StreamConnection {
        override val input: InputStream = object : InputStream() {
            private var pending: ByteArray = EMPTY
            private var pos = 0
            private var eof = false

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (pos >= pending.size) {
                    if (eof) return -1
                    val chunk = try {
                        conn.read(maxOf(len, READ_CHUNK).toLong())
                    } catch (e: Exception) {
                        throw IOException("tunnel: ${e.message}")
                    }
                    if (chunk == null || chunk.isEmpty()) {
                        eof = true
                        return -1
                    }
                    pending = chunk
                    pos = 0
                }
                val n = minOf(len, pending.size - pos)
                System.arraycopy(pending, pos, b, off, n)
                pos += n
                return n
            }
        }

        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len == 0) return
                val data = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
                try {
                    conn.write(data)
                } catch (e: Exception) {
                    throw IOException("tunnel: ${e.message}")
                }
            }
        }

        override fun shutdownOutput() {
            runCatching { conn.closeWrite() }
        }

        override fun close() {
            runCatching { conn.close() }
        }
    }

    private class BridgeDatagram(private val pc: xraybridge.PacketConn) : DatagramFlow {
        override fun send(buf: ByteArray, offset: Int, length: Int) {
            try {
                pc.send(buf.copyOfRange(offset, offset + length))
            } catch (e: Exception) {
                throw IOException("tunnel: ${e.message}")
            }
        }

        override fun receive(buf: ByteArray): Int {
            val pkt = try {
                pc.receive(UDP_POLL_MS.toLong())
            } catch (e: Exception) {
                if (e.message == "timeout") throw SocketTimeoutException()
                throw IOException("tunnel: ${e.message}")
            }
            val n = minOf(pkt.size, buf.size)
            System.arraycopy(pkt, 0, buf, 0, n)
            return n
        }

        override fun close() {
            runCatching { pc.close() }
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val READ_CHUNK = 16 * 1024
        /** Same poll interval as the direct UDP socket (UdpFlow treats a timeout as "keep going"). */
        const val UDP_POLL_MS = 1000
        const val DNS_TIMEOUT_MS = 5000
        const val TUNNEL_DNS = "1.1.1.1"
    }
}
