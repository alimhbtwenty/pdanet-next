package io.github.alimhbtwenty.pdanext.core.router

import io.github.alimhbtwenty.pdanext.core.LogLevel
import io.github.alimhbtwenty.pdanext.core.LogSource
import io.github.alimhbtwenty.pdanext.core.RelayServer
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The PC side of the "phone as a router" mode, over the adb-forwarded TCP stream.
 *
 *   PC -> phone:  "PDNR" version(1)
 *   phone -> PC:  "PDNR" version(1) gateway4(4) client4(4) prefix4(1) gateway6(16) client6(16) prefix6(1) mtu(2)
 *   then both ways: frames of length(u16 BE) + one IP packet. Length 0 is a keep-alive.
 *
 * The phone sends the addressing so the PC configures its adapter from a single source of truth.
 */
class RouterLink(
    private val relay: RelayServer,
    private val client: Socket,
    input: InputStream,
    private val config: RouterConfig,
    private val platform: RouterPlatform,
    private val tunnel: Upstream? = null,
    private val policy: BypassPolicy = BypassPolicy(),
) {
    private val input = DataInputStream(input)
    private val out = DataOutputStream(BufferedOutputStream(client.getOutputStream(), 256 * 1024))
    // A full queue drops packets like a congested router would; TCP recovers.
    private val outbox = ArrayBlockingQueue<ByteArray>(8192)
    @Volatile private var closed = false

    lateinit var router: Router
        private set

    fun run() {
        val magic = ByteArray(4).also { input.readFully(it) }
        if (String(magic, Charsets.US_ASCII) != MAGIC) throw IOException("bad link magic")
        val version = input.readUnsignedByte()
        if (version != VERSION) throw IOException("unsupported link version $version")

        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.write(VERSION)
        out.write(config.gateway4.address)
        out.write(config.client4.address)
        out.write(config.prefix4)
        out.write(config.gateway6.address)
        out.write(config.client6.address)
        out.write(config.prefix6)
        out.writeShort(config.mtu)
        out.flush()

        client.soTimeout = 0
        client.tcpNoDelay = true
        router = Router(
            config = config,
            sendToPc = ::enqueue,
            direct = DirectUpstream(platform),
            tunnel = tunnel,
            policy = policy,
            stats = relay.stats,
            platform = platform,
            log = { relay.log("router: $it") },
            logger = { lvl, src, msg -> relay.log(lvl, src, msg) },
            isProtectedProvider = relay.isProtected ?: { (relay.statusExtras()["vpnActive"] as? Boolean) == true },
        )
        relay.attachRouter(this)
        relay.log(LogLevel.INFO, LogSource.LINK, "PC connected in router mode")
        val writer = Thread(::writeLoop, "router-link-writer").apply { isDaemon = true; start() }
        val buf = ByteArray(65535)
        var disconnectReason = "link closed normally"
        try {
            while (!closed) {
                val len = input.readUnsignedShort()
                if (len == 0) continue
                input.readFully(buf, 0, len)
                router.onPacket(buf, len)
            }
        } catch (_: EOFException) {
            disconnectReason = "EOF / connection closed by PC"
        } catch (e: Exception) {
            disconnectReason = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            close()
            writer.interrupt()
            relay.detachRouter(this)
            relay.log(LogLevel.INFO, LogSource.LINK, "PC disconnected from router mode: $disconnectReason")
        }
    }

    private fun enqueue(packet: ByteArray) {
        if (!closed) outbox.offer(packet)
    }

    private fun writeLoop() {
        try {
            while (!closed) {
                val first = outbox.poll(15, TimeUnit.SECONDS)
                if (first == null) {
                    out.writeShort(0) // keep-alive so a dead link is noticed
                    out.flush()
                    continue
                }
                write(first)
                // Batch whatever else is ready before one flush.
                while (true) write(outbox.poll() ?: break)
                out.flush()
            }
        } catch (_: InterruptedException) {
        } catch (e: IOException) {
            relay.log(LogLevel.WARN, LogSource.LINK, "Router link write / keep-alive failed: ${e.message}")
            close()
        }
    }

    private fun write(packet: ByteArray) {
        out.writeShort(packet.size)
        out.write(packet)
    }

    fun close() {
        if (closed) return
        closed = true
        if (::router.isInitialized) router.shutdown()
        RelayServer.closeQuietly(client)
    }

    companion object {
        const val MAGIC = "PDNR"
        const val VERSION = 1
    }
}
