package io.github.alimhbtwenty.pdanetnext.core

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
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
 * The phone-side relay. One TCP port that speaks:
 *  - SOCKS5 (CONNECT), including sing-box UDP-over-TCP v2 for UDP traffic,
 *  - HTTP proxy (CONNECT and absolute-form requests), for the PC's proxy-only mode,
 *  - `GET /status`, a JSON snapshot for the PC client.
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
) {
    val stats = RelayStats()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var executor: ExecutorService? = null
    private val openSockets = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())

    val isRunning: Boolean get() = serverSocket?.isClosed == false

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
        stats.totalConnections.incrementAndGet()
        stats.activeTcp.incrementAndGet()
        try {
            client.tcpNoDelay = true
            client.keepAlive = true
            // Only the handshake has a deadline; established tunnels may idle forever.
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream(), 16 * 1024)
            input.mark(1)
            val first = input.read()
            if (first < 0) return
            input.reset()
            if (first == 0x05) {
                Socks5Session(this, client, DataInputStream(input)).run()
            } else {
                HttpProxySession(this, client, input).run()
            }
        } catch (e: Exception) {
            if (e !is SocketException) log("session error: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            stats.activeTcp.decrementAndGet()
            untrack(client)
            closeQuietly(client)
        }
    }

    internal fun track(socket: Socket) { openSockets.add(socket) }
    internal fun untrack(socket: Socket) { openSockets.remove(socket) }
    internal fun log(message: String) = log.invoke(message)
    internal fun execute(task: Runnable) {
        executor?.execute(task) ?: throw IOException("relay stopped")
    }

    /** Opens an outbound TCP connection from the phone. */
    internal fun connect(dest: Destination): Socket {
        val socket = Socket()
        try {
            val address = dest.ip?.let { InetSocketAddress(it, dest.port) } ?: InetSocketAddress(dest.host, dest.port)
            if (address.isUnresolved) throw java.net.UnknownHostException(dest.host)
            socket.connect(address, CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            socket.keepAlive = true
            track(socket)
            return socket
        } catch (e: Exception) {
            stats.failedConnections.incrementAndGet()
            closeQuietly(socket)
            throw e
        }
    }

    /**
     * Copies both directions until each side has finished, half-closing as it goes so
     * request/response protocols complete. [clientIn] may hold bytes already buffered.
     */
    internal fun bridge(client: Socket, clientIn: InputStream, target: Socket) {
        client.soTimeout = 0
        target.soTimeout = 0
        val upstreamDone = java.util.concurrent.CountDownLatch(1)
        execute {
            try {
                copy(clientIn, target.getOutputStream(), stats.bytesUp)
                runCatching { target.shutdownOutput() }
            } catch (e: IOException) {
                closeQuietly(target); closeQuietly(client)
            } finally {
                upstreamDone.countDown()
            }
        }
        try {
            copy(target.getInputStream(), client.getOutputStream(), stats.bytesDown)
            runCatching { client.shutdownOutput() }
        } catch (e: IOException) {
            closeQuietly(target); closeQuietly(client)
        }
        upstreamDone.await()
        untrack(target)
        closeQuietly(target)
    }

    internal fun statusJson(): String {
        val fields = linkedMapOf<String, Any?>(
            "app" to "pdanet-next",
            "version" to VERSION,
            "bytesUp" to stats.bytesUp.get(),
            "bytesDown" to stats.bytesDown.get(),
            "activeTcp" to stats.activeTcp.get(),
            "activeUdp" to stats.activeUdp.get(),
            "totalConnections" to stats.totalConnections.get(),
            "failedConnections" to stats.failedConnections.get(),
        )
        fields.putAll(runCatching(statusExtras).getOrDefault(emptyMap()))
        return Json.encode(fields)
    }

    companion object {
        const val DEFAULT_PORT = 28080
        const val VERSION = "3.0.0"
        const val HANDSHAKE_TIMEOUT_MS = 15_000
        const val CONNECT_TIMEOUT_MS = 10_000

        fun copy(input: InputStream, output: java.io.OutputStream, counter: java.util.concurrent.atomic.AtomicLong) {
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return
                output.write(buffer, 0, n)
                output.flush()
                counter.addAndGet(n.toLong())
            }
        }

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
