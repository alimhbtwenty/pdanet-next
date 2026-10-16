package io.github.alimhbtwenty.pdanext.core

import io.github.alimhbtwenty.pdanext.core.router.BypassPolicy
import io.github.alimhbtwenty.pdanext.core.router.JvmRouterPlatform
import io.github.alimhbtwenty.pdanext.core.router.Router
import io.github.alimhbtwenty.pdanext.core.router.RouterConfig
import io.github.alimhbtwenty.pdanext.core.router.RouterLink
import io.github.alimhbtwenty.pdanext.core.router.RouterPlatform
import io.github.alimhbtwenty.pdanext.core.router.Upstream
import io.github.alimhbtwenty.pdanext.core.xray.JsonHelper
import io.github.alimhbtwenty.pdanext.core.xray.XrayControl
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
 *  - `GET /ping-pc`: an ICMP echo from the phone (the router) to the PC;
 *  - `/xray/...`: PC control for Xray profiles and bypass domains.
 *
 * Every outgoing socket is opened by this process, so the phone's own routing decides
 * where it goes: mobile data, WiFi, or an active VPN.
 */
class RelayServer(
    val port: Int = DEFAULT_PORT,
    private val bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
    internal val log: (String) -> Unit = {},
    /** Extra fields for the status endpoint, e.g. whether a phone VPN is active. */
    internal val statusExtras: () -> Map<String, Any?> = { emptyMap() },
    private val routerConfig: RouterConfig = RouterConfig(),
    private val routerPlatform: RouterPlatform = JvmRouterPlatform(),
    private val tunnelUpstream: Upstream? = null,
    /** PC-managed Xray profiles (the `/xray/` endpoints); its selected profile is the tunnel. */
    val xrayControl: XrayControl? = null,
    /** Current tunnel for new flows (e.g. the selected Xray config); null = everything DIRECT. */
    private val tunnelProvider: () -> Upstream? = { xrayControl?.activeTunnel ?: tunnelUpstream },
    private val bypassPolicy: BypassPolicy = BypassPolicy(),
    val logBuffer: LogRingBuffer = LogRingBuffer(),
    val requireProtection: Boolean = routerConfig.requireProtection,
    val isProtected: (() -> Boolean)? = null,
    val failureLogger: FailureFileLogger? = null,
) {
    val stats = RelayStats()

    init {
        if (failureLogger != null) {
            logBuffer.addListener { entry, _ ->
                failureLogger.append(entry)
            }
        }
    }

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
                if (!server.isClosed) log(LogLevel.WARN, LogSource.SERVICE, "accept failed: ${e.message}")
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
            val headStr = String(head, Charsets.US_ASCII)
            when {
                headStr == RouterLink.MAGIC -> RouterLink(this, client, input, routerConfig, routerPlatform, tunnelProvider, bypassPolicy).run()
                headStr.startsWith("GET ") || headStr == "POST" -> serveHttp(client, input, client.getOutputStream())
            }
        } catch (e: Exception) {
            if (e !is SocketException) log("session error: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            untrack(client)
            closeQuietly(client)
        }
    }

    private fun readHttpLine(input: InputStream): String? {
        val line = StringBuilder()
        while (line.length < 8192) {
            val b = input.read()
            if (b < 0) return if (line.isNotEmpty()) line.toString() else null
            if (b == '\n'.code) {
                if (line.isNotEmpty() && line[line.length - 1] == '\r') {
                    line.setLength(line.length - 1)
                }
                return line.toString()
            }
            line.append(b.toChar())
        }
        return line.toString()
    }

    /** Tiny HTTP/1.1 responder for the PC app's status, ping, log, and xray control requests. */
    private fun serveHttp(client: Socket, input: InputStream, output: OutputStream) {
        val firstLine = readHttpLine(input) ?: return
        val parts = firstLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]
        val path = target.substringBefore('?')

        var contentLength: Int? = null
        while (true) {
            val h = readHttpLine(input) ?: break
            if (h.isEmpty()) break
            val colon = h.indexOf(':')
            if (colon > 0) {
                val name = h.substring(0, colon).trim()
                val value = h.substring(colon + 1).trim()
                if (name.equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.toIntOrNull()
                }
            }
        }

        val bodyText: String
        if (method == "POST") {
            val len = contentLength
            if (len == null || len < 0 || len > 1024 * 1024) {
                sendHttpError(output, 400, "Bad Request: invalid Content-Length")
                return
            }
            val buf = ByteArray(len)
            var read = 0
            while (read < len) {
                val r = input.read(buf, read, len - read)
                if (r < 0) {
                    sendHttpError(output, 400, "Bad Request: unexpected end of stream")
                    return
                }
                read += r
            }
            bodyText = String(buf, Charsets.UTF_8)
        } else {
            bodyText = ""
        }

        if (path == "/log" || path == "/log/failures" || path.startsWith("/xray/")) {
            if (!client.inetAddress.isLoopbackAddress) {
                sendHttpError(output, 403, "403 Forbidden: loopback only")
                return
            }
        }

        if (path == "/log" && method == "GET") {
            val since = parseQueryParam(target, "since")?.toLongOrNull()
            // Text and sequence come from the same snapshot, so a line logged meanwhile is not skipped.
            val entries = logBuffer.dump(since)
            val text = entries.joinToString("") { it.format() + "\n" }
            val lastSeq = entries.lastOrNull()?.sequence ?: (since ?: 0L)
            sendHttpText(output, 200, text, "X-Log-Seq: $lastSeq\r\nX-Log-Boot: ${logBuffer.bootId}\r\n")
            return
        }

        if (path == "/log/failures" && method == "GET") {
            sendHttpText(output, 200, failureLogger?.readAllText() ?: "")
            return
        }

        if (path == "/status" && method == "GET") {
            sendHttpJson(output, 200, statusJson())
            return
        }

        if (path == "/ping-pc" && method == "GET") {
            sendHttpJson(output, 200, "{\"ms\":${activeRouter?.pingClient() ?: "null"}}")
            return
        }

        if (path.startsWith("/xray/")) {
            val control = xrayControl
            if (control == null) {
                sendHttpError(output, 404, "404 Not Found")
                return
            }

            when {
                path == "/xray/profiles" && method == "GET" -> {
                    val profiles = control.store.list()
                    val selId = control.selectedId
                    val json = profiles.joinToString(prefix = "[", postfix = "]") { p ->
                        "{\"id\":${Json.quote(p.id)},\"name\":${Json.quote(p.name)},\"selected\":${p.id == selId}}"
                    }
                    sendHttpJson(output, 200, json)
                    return
                }

                path == "/xray/import" && method == "POST" -> {
                    val added = control.importLinks(bodyText)
                    val json = added.joinToString(prefix = "[", postfix = "]") { Json.quote(it) }
                    sendHttpJson(output, 200, json)
                    return
                }

                path == "/xray/select" && method == "POST" -> {
                    val map = JsonHelper.parseObject(bodyText)
                    if (map == null) {
                        sendHttpError(output, 400, "Bad Request: malformed JSON")
                        return
                    }
                    val targetId = map["id"]
                    try {
                        control.select(targetId)
                        sendHttpJson(output, 200, "{\"ok\":true}")
                    } catch (e: IllegalArgumentException) {
                        sendHttpError(output, 400, "Bad Request: " + (e.message ?: "unknown profile"))
                    } catch (e: Exception) {
                        sendHttpError(output, 400, "Bad Request: failed to start profile")
                    }
                    return
                }

                path == "/xray/delete" && method == "POST" -> {
                    val map = JsonHelper.parseObject(bodyText)
                    if (map == null) {
                        sendHttpError(output, 400, "Bad Request: malformed JSON")
                        return
                    }
                    val targetId = map["id"]
                    if (targetId == null) {
                        sendHttpError(output, 400, "Bad Request: missing id")
                        return
                    }
                    val deleted = control.delete(targetId)
                    sendHttpJson(output, 200, "{\"ok\":$deleted}")
                    return
                }

                path == "/xray/delay" && method == "POST" -> {
                    val map = JsonHelper.parseObject(bodyText)
                    if (map == null) {
                        sendHttpError(output, 400, "Bad Request: malformed JSON")
                        return
                    }
                    val targetId = map["id"]
                    if (targetId == null) {
                        sendHttpError(output, 400, "Bad Request: missing id")
                        return
                    }
                    if (control.store.get(targetId) == null) {
                        sendHttpError(output, 400, "Bad Request: unknown profile")
                        return
                    }
                    val ms = control.measureDelay(targetId)
                    sendHttpJson(output, 200, "{\"ms\":${ms ?: "null"}}")
                    return
                }

                path == "/xray/bypass" && method == "GET" -> {
                    val domains = control.getBypassDomains()
                    val json = domains.joinToString(prefix = "[", postfix = "]") { Json.quote(it) }
                    sendHttpJson(output, 200, json)
                    return
                }

                path == "/xray/bypass" && method == "POST" -> {
                    val domains = if (bodyText.trimStart().startsWith("[")) {
                        val parsed = JsonHelper.parseStringArray(bodyText)
                        if (parsed == null) {
                            sendHttpError(output, 400, "Bad Request: malformed JSON array")
                            return
                        }
                        parsed
                    } else {
                        bodyText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                    }
                    control.setBypassDomains(domains)
                    val updated = control.getBypassDomains()
                    val json = updated.joinToString(prefix = "[", postfix = "]") { Json.quote(it) }
                    sendHttpJson(output, 200, json)
                    return
                }

                else -> {
                    sendHttpError(output, 404, "404 Not Found")
                    return
                }
            }
        }

        sendHttpError(output, 404, "404 Not Found")
    }

    private fun sendHttpJson(output: OutputStream, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val statusText = if (status == 200) "200 OK" else "$status Error"
        output.write(
            ("HTTP/1.1 $statusText\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        output.write(bytes)
        output.flush()
    }

    private fun sendHttpText(output: OutputStream, status: Int, text: String, extraHeaders: String = "") {
        val bytes = text.toByteArray(Charsets.UTF_8)
        output.write(
            ("HTTP/1.1 $status OK\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\n" +
                extraHeaders +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        output.write(bytes)
        output.flush()
    }

    private fun sendHttpError(output: OutputStream, status: Int, message: String) {
        val statusText = when (status) {
            400 -> "400 Bad Request"
            403 -> "403 Forbidden"
            404 -> "404 Not Found"
            else -> "$status Error"
        }
        val bytes = message.toByteArray(Charsets.UTF_8)
        output.write(
            ("HTTP/1.1 $statusText\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        output.write(bytes)
        output.flush()
    }

    private fun parseQueryParam(url: String, key: String): String? {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val parts = pair.split('=', limit = 2)
            if (parts[0] == key && parts.size == 2) {
                return parts[1]
            }
        }
        return null
    }

    private fun track(socket: Socket) { openSockets.add(socket) }
    private fun untrack(socket: Socket) { openSockets.remove(socket) }
    internal fun log(message: String) {
        logBuffer.log(LogLevel.INFO, LogSource.SERVICE, message)
        log.invoke(message)
    }

    internal fun log(level: LogLevel, source: LogSource, message: String) {
        logBuffer.log(level, source, message)
        log.invoke("[$source] $message")
    }

    internal fun statusJson(): String {
        val isRouter = link != null
        val router = activeRouter
        val ks = router?.requireProtection ?: requireProtection
        val prot = router?.isProtected ?: (isProtected?.invoke() ?: (statusExtras()["vpnActive"] == true))
        val fields = linkedMapOf<String, Any?>(
            "app" to "pdanext",
            "version" to VERSION,
            "bytesUp" to stats.bytesUp.get(),
            "bytesDown" to stats.bytesDown.get(),
            "activeTcp" to stats.activeTcp.get(),
            "activeUdp" to stats.activeUdp.get(),
            "totalConnections" to stats.totalConnections.get(),
            "failedConnections" to stats.failedConnections.get(),
            "routerMode" to isRouter,
            "protected" to prot,
            "killSwitch" to ks,
            "iranRanges" to bypassPolicy.ranges.size,
            "xrayProfile" to xrayControl?.selectedName,
        )
        fields.putAll(runCatching(statusExtras).getOrDefault(emptyMap()))
        fields["protected"] = prot
        fields["killSwitch"] = ks
        return Json.encode(fields)
    }

    companion object {
        const val DEFAULT_PORT = 28080
        const val VERSION = "3.1.1"
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

    internal fun quote(s: String): String {
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
