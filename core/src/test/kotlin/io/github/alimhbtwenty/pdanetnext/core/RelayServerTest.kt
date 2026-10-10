package io.github.alimhbtwenty.pdanetnext.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayServerTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private lateinit var relay: RelayServer
    private lateinit var tcpEcho: ServerSocket
    private lateinit var udpEcho: DatagramSocket

    @BeforeTest
    fun setUp() {
        relay = RelayServer(port = freePort(), statusExtras = { mapOf("vpnActive" to true) })
        relay.start()

        tcpEcho = ServerSocket(0, 50, loopback)
        thread(isDaemon = true) {
            while (!tcpEcho.isClosed) {
                val s = runCatching { tcpEcho.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use { it.getInputStream().copyTo(it.getOutputStream()) }
                }
            }
        }

        udpEcho = DatagramSocket(0, loopback)
        thread(isDaemon = true) {
            val buf = ByteArray(65535)
            while (!udpEcho.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { udpEcho.receive(p) }.getOrNull() ?: break
                udpEcho.send(DatagramPacket(p.data, p.length, p.socketAddress))
            }
        }
    }

    @AfterTest
    fun tearDown() {
        relay.stop()
        tcpEcho.close()
        udpEcho.close()
    }

    @Test
    fun `socks5 connect relays tcp both ways, including large payloads`() {
        socks(loopback.hostAddress, tcpEcho.localPort).use { (s, input, out) ->
            val payload = ByteArray(1_000_000) { (it % 251).toByte() }
            thread { out.write(payload); out.flush() }
            val back = ByteArray(payload.size)
            input.readFully(back)
            assertContentEquals(payload, back)
        }
        assertTrue(relay.stats.bytesUp.get() >= 1_000_000)
        assertTrue(relay.stats.bytesDown.get() >= 1_000_000)
    }

    @Test
    fun `socks5 connect by domain name resolves on the relay side`() {
        socks("localhost", tcpEcho.localPort).use { (_, input, out) ->
            out.write("hello".toByteArray()); out.flush()
            val back = ByteArray(5).also { input.readFully(it) }
            assertEquals("hello", String(back))
        }
    }

    @Test
    fun `half close lets the response finish`() {
        // Server reads until EOF, then answers: needs the relay to forward the half-close.
        val server = ServerSocket(0, 1, loopback)
        thread(isDaemon = true) {
            server.accept().use { s ->
                val got = s.getInputStream().readBytes()
                s.getOutputStream().write("got ${got.size}".toByteArray())
            }
        }
        socks(loopback.hostAddress, server.localPort).use { (s, input, out) ->
            out.write(ByteArray(1234)); out.flush()
            s.shutdownOutput()
            assertEquals("got 1234", String(input.readBytes()))
        }
        server.close()
    }

    @Test
    fun `tunnel survives idle periods longer than the handshake timeout`() {
        val idleMs = RelayServer.HANDSHAKE_TIMEOUT_MS + 1500L
        socks(loopback.hostAddress, tcpEcho.localPort).use { (_, input, out) ->
            Thread.sleep(idleMs)
            out.write(1); out.flush()
            assertEquals(1, input.read())
        }
    }

    @Test
    fun `refused connection returns socks error instead of hanging`() {
        val closedPort = freePort()
        Socket(loopback, relay.port).use { s ->
            val out = DataOutputStream(s.getOutputStream())
            val input = DataInputStream(s.getInputStream())
            out.write(byteArrayOf(5, 1, 0)); out.flush()
            assertContentEquals(byteArrayOf(5, 0), ByteArray(2).also { input.readFully(it) })
            out.write(byteArrayOf(5, 1, 0) + AddressCodec.SOCKS.encode(loopback, closedPort)); out.flush()
            assertEquals(5, input.readUnsignedByte())
            assertEquals(Socks5Session.REP_CONNECTION_REFUSED, input.readUnsignedByte())
        }
    }

    @Test
    fun `udp over tcp v2 relays datagrams with per-packet destinations`() {
        uot(isConnect = false).use { (_, input, out) ->
            repeat(3) { i ->
                val payload = "ping $i".toByteArray()
                AddressCodec.UOT.write(out, loopback, udpEcho.localPort)
                out.writeShort(payload.size); out.write(payload); out.flush()

                val from = AddressCodec.UOT.read(input)
                assertEquals(udpEcho.localPort, from.port)
                val reply = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
                assertEquals("ping $i", String(reply))
            }
            assertEquals(1, relay.stats.activeUdp.get())
        }
    }

    @Test
    fun `udp over tcp v2 connect mode has no per-packet address`() {
        uot(isConnect = true).use { (_, input, out) ->
            val payload = ByteArray(1400) { it.toByte() }
            out.writeShort(payload.size); out.write(payload); out.flush()
            val reply = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
            assertContentEquals(payload, reply)
        }
    }

    @Test
    fun `bad udp destination does not kill the session`() {
        uot(isConnect = false).use { (_, input, out) ->
            // Unresolvable name first, then a good packet on the same session.
            val bad = "nonexistent.invalid".toByteArray()
            out.write(byteArrayOf(0x02, bad.size.toByte()) + bad + byteArrayOf(0, 53))
            out.writeShort(1); out.write(0)
            AddressCodec.UOT.write(out, loopback, udpEcho.localPort)
            out.writeShort(2); out.write(byteArrayOf(7, 7)); out.flush()

            AddressCodec.UOT.read(input)
            assertEquals(2, input.readUnsignedShort())
        }
    }

    @Test
    fun `http proxy CONNECT tunnels`() {
        Socket(loopback, relay.port).use { s ->
            val out = s.getOutputStream()
            out.write("CONNECT 127.0.0.1:${tcpEcho.localPort} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); out.flush()
            val input = DataInputStream(s.getInputStream())
            val status = readHead(input)
            assertTrue(status.startsWith("HTTP/1.1 200"), status)
            out.write("abc".toByteArray()); out.flush()
            assertEquals("abc", String(ByteArray(3).also { input.readFully(it) }))
        }
    }

    @Test
    fun `http proxy forwards absolute-form requests with Connection close`() {
        val origin = ServerSocket(0, 1, loopback)
        var seen = ""
        thread(isDaemon = true) {
            origin.accept().use { s ->
                seen = readHead(DataInputStream(s.getInputStream()))
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
            }
        }
        Socket(loopback, relay.port).use { s ->
            s.getOutputStream().write(
                ("GET http://127.0.0.1:${origin.localPort}/a/b?c=1 HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Proxy-Connection: keep-alive\r\nConnection: keep-alive\r\n\r\n").toByteArray()
            )
            val response = String(s.getInputStream().readBytes())
            assertTrue(response.endsWith("ok"), response)
        }
        assertTrue(seen.startsWith("GET /a/b?c=1 HTTP/1.1"), seen)
        assertTrue("Connection: close" in seen, seen)
        assertTrue("keep-alive" !in seen.lowercase(), seen)
        origin.close()
    }

    @Test
    fun `status endpoint returns json with extras`() {
        Socket(loopback, relay.port).use { s ->
            s.getOutputStream().write("GET /status HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val response = String(s.getInputStream().readBytes())
            assertTrue(response.startsWith("HTTP/1.1 200"), response)
            assertTrue("\"app\":\"pdanet-next\"" in response, response)
            assertTrue("\"vpnActive\":true" in response, response)
        }
    }

    @Test
    fun `parse authority handles ipv6 and default ports`() {
        assertEquals(Destination("::1", 443), HttpProxySession.parseAuthority("[::1]:443", 80))
        assertEquals(Destination("example.com", 443), HttpProxySession.parseAuthority("example.com", 443))
        assertEquals(null, HttpProxySession.parseAuthority("example.com:99999", 443))
    }

    @Test
    fun `stop closes live tunnels`() {
        val (s, input, _) = socks(loopback.hostAddress, tcpEcho.localPort)
        relay.stop()
        assertEquals(-1, runCatching { input.read() }.getOrDefault(-1))
        s.close()
    }

    // --- helpers ---

    private data class Conn(val socket: Socket, val input: DataInputStream, val out: DataOutputStream) : AutoCloseable {
        override fun close() = socket.close()
    }

    private fun handshake(dest: ByteArray): Conn {
        val s = Socket(loopback, relay.port)
        s.soTimeout = 30_000
        val out = DataOutputStream(s.getOutputStream())
        val input = DataInputStream(s.getInputStream())
        out.write(byteArrayOf(5, 1, 0)); out.flush()
        assertContentEquals(byteArrayOf(5, 0), ByteArray(2).also { input.readFully(it) })
        out.write(byteArrayOf(5, 1, 0) + dest); out.flush()
        assertEquals(5, input.readUnsignedByte())
        assertEquals(0, input.readUnsignedByte(), "socks reply code")
        input.readUnsignedByte()
        AddressCodec.SOCKS.read(input)
        return Conn(s, input, out)
    }

    private fun socks(host: String, port: Int): Conn {
        val ip = runCatching { InetAddress.getByName(host) }.getOrNull()
        val dest = if (host.first().isDigit() && ip != null) AddressCodec.SOCKS.encode(ip, port) else domain(0x03, host, port)
        return handshake(dest)
    }

    private fun uot(isConnect: Boolean): Conn {
        val conn = handshake(domain(0x03, Socks5Session.UOT_MAGIC_V2, 0))
        conn.out.writeBoolean(isConnect)
        conn.out.write(AddressCodec.SOCKS.encode(loopback, udpEcho.localPort))
        conn.out.flush()
        return conn
    }

    private fun domain(type: Int, name: String, port: Int): ByteArray =
        byteArrayOf(type.toByte(), name.length.toByte()) + name.toByteArray() + byteArrayOf((port shr 8).toByte(), port.toByte())

    private fun readHead(input: DataInputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) sb.append(input.readUnsignedByte().toChar())
        return sb.toString()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
