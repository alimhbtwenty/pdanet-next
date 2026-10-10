package io.github.alimhbtwenty.pdanext.core

import io.github.alimhbtwenty.pdanext.core.router.JvmRouterPlatform
import io.github.alimhbtwenty.pdanext.core.router.Router
import io.github.alimhbtwenty.pdanext.core.router.RouterConfig
import io.github.alimhbtwenty.pdanext.core.router.RouterLink
import io.github.alimhbtwenty.pdanext.core.router.RouterPlatform
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone side of PdaNext, on one loopback port reached by the PC through `adb forward`:
 *  - the router link ("PDNR"): raw IP packets from the PC's network adapter, NAT-ed by [Router];
 *  - `GET /status`: JSON counters plus the phone's uplink/VPN state, polled by the PC app;
 *  - `GET /ping-pc`: an ICMP echo from the phone (the router) to the PC.
 *
 * Every outgoing socket is opened by this process, so the phone's own routing decides
 * where it goes: mobile data, WiFi, or an active VPN.
 */
class RelayServer(
    val port: Int = DEFAULT_PORT,
    private val bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
    private val log: (String) -> Unit = {},
    /** Extra fields for the status endpoint, e.g. whether a phone VPN is active. */
    private val statusExtras: () -> Map<String, Any?> = { emptyMap() },
    private val routerConfig: RouterConfig = RouterConfig(),
    private val routerPlatform: RouterPlatform = JvmRouterPlatform(),
) {
    val stats = RelayStats()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var executor: ExecutorService? = null
    private val openSockets = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    @Volatile private var link: RouterLink? = null

    /** The router serving the connected PC, if one is attached in router mode. */
    val activeRouter: Router? get() = link?.router

    /** One PC at a time: a new link (e.g. after a reconnect) replaces the old one. */
    @Synchronized
    internal fun attachRouter(newLink: RouterLink) {
        link?.close()
        link = newLink
    }

    @Synchronized
    internal fun detachRouter(old: RouterLink) {
        if (link === old) link = null
    }

    /** Binds synchronously (so callers learn about a busy port right away), then accepts in the background. */
    @Synchronized
    fun start() {
        if (isRunning) return
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(bindAddress, port), 256)
        val pool = Executors.newCachedThreadPool(daemonThreads("relay"))
        serverSocket = server
        executor = pool
        pool.execute { acceptLoop(server, pool) }
        log("relay listening on ${bindAddress.hostAddress}:$port")
    }

    @Synchronized
    fun stop() {
        serverSocket?.let { closeQuietly(it) }
        serverSocket = null
        link?.close()
        link = null
        openSockets.forEach { closeQuietly(it) }
        openSockets.clear()
        executor?.shutdownNow()
        executor = null
        log("relay stopped")
    }

    private fun acceptLoop(server: ServerSocket, pool: ExecutorService) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (!server.isClosed) log("accept failed: ${e.message}")
                continue
            }
            try {
                pool.execute { handle(client) }
            } catch (e: Exception) {
                closeQuietly(client)
            }
        }
    }

    private fun handle(client: Socket) {
        track(client)
        try {
            client.tcpNoDelay = true
            client.keepAlive = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream(), 16 * 1024)
            input.mark(8)
            val head = ByteArray(4)
            var n = 0
            while (n < 4) {
                val r = input.read(head, n, 4 - n)
                if (r < 0) return
                n += r
            }
            input.reset()
            when (String(head, Charsets.US_ASCII)) {
                RouterLink.MAGIC -> RouterLink(this, client, input, routerConfig, routerPlatform).run()
                "GET " -> serveHttp(input, client.getOutputStream())
            }
        } catch (e: Exception) {
            if (e !is SocketException) log("session error: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            untrack(client)
            closeQuietly(client)
        }
    }

    /** Tiny HTTP/1.1 responder for the PC app's status and ping requests. */
    private fun serveHttp(input: InputStream, output: OutputStream) {
        val line = StringBuilder()
        while (line.length < 1024) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            line.append(b.toChar())
        }
        val path = line.split(' ').getOrNull(1)?.substringBefore('?')
        val body = when (path) {
            "/status" -> statusJson()
            "/ping-pc" -> "{\"ms\":${activeRouter?.pingClient() ?: "null"}}"
            else -> null
        }
        val status = if (body != null) "200 OK" else "404 Not Found"
        val bytes = (body ?: "{}").toByteArray()
        output.write(
            ("HTTP/1.1 $status\r\nContent-Type: application/json\r\nCache-Control: no-store\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        output.write(bytes)
        output.flush()
    }

    private fun track(socket: Socket) { openSockets.add(socket) }
    private fun untrack(socket: Socket) { openSockets.remove(socket) }
    internal fun log(message: String) = log.invoke(message)

    internal fun statusJson(): String {
        val fields = linkedMapOf<String, Any?>(
            "app" to "pdanext",
            "version" to VERSION,
            "bytesUp" to stats.bytesUp.get(),
            "bytesDown" to stats.bytesDown.get(),
            "activeTcp" to stats.activeTcp.get(),
            "activeUdp" to stats.activeUdp.get(),
            "totalConnections" to stats.totalConnections.get(),
            "failedConnections" to stats.failedConnections.get(),
            "routerMode" to (link != null),
        )
        fields.putAll(runCatching(statusExtras).getOrDefault(emptyMap()))
        return Json.encode(fields)
    }

    companion object {
        const val DEFAULT_PORT = 28080
        const val VERSION = "3.0.0"
        const val HANDSHAKE_TIMEOUT_MS = 15_000

        fun closeQuietly(c: java.io.Closeable?) {
            try { c?.close() } catch (_: Exception) {}
        }

        fun daemonThreads(prefix: String): ThreadFactory {
            val n = AtomicInteger()
            return ThreadFactory { r -> Thread(r, "$prefix-${n.incrementAndGet()}").apply { isDaemon = true } }
        }
    }
}

/** Minimal JSON writer for flat maps of strings, numbers, booleans and nulls. */
internal object Json {
    fun encode(map: Map<String, Any?>): String = map.entries.joinToString(",", "{", "}") { (k, v) ->
        "${quote(k)}:${value(v)}"
    }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Number, is Boolean -> v.toString()
        else -> quote(v.toString())
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
