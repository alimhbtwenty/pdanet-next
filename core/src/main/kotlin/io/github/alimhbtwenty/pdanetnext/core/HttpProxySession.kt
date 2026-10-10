package io.github.alimhbtwenty.pdanetnext.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.URI

/**
 * HTTP proxy for the PC's proxy-only mode (Windows system proxy), plus the status endpoint.
 * Plain HTTP requests are forwarded with `Connection: close`, one request per client connection.
 */
internal class HttpProxySession(
    private val relay: RelayServer,
    private val client: Socket,
    private val input: InputStream,
) {
    private val output = client.getOutputStream()

    fun run() {
        val head = readHead() ?: return
        val requestLine = head.first().split(' ')
        if (requestLine.size < 3) return respond(400, "Bad Request")
        val (method, target) = requestLine.let { it[0].uppercase() to it[1] }

        when {
            method == "CONNECT" -> connect(target)
            target.startsWith("/") -> local(method, target)
            else -> forward(method, target, requestLine[2], head.drop(1))
        }
    }

    private fun connect(authority: String) {
        val dest = parseAuthority(authority, 443) ?: return respond(400, "Bad Request")
        val upstream = try {
            relay.connect(dest)
        } catch (e: Exception) {
            relay.log("CONNECT $dest failed: ${e.message}")
            return respond(502, "Bad Gateway")
        }
        try {
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            output.flush()
            relay.bridge(client, input, upstream)
        } finally {
            relay.untrack(upstream)
            RelayServer.closeQuietly(upstream)
        }
    }

    private fun forward(method: String, target: String, version: String, headers: List<String>) {
        val uri = runCatching { URI(target) }.getOrNull()
        if (uri == null || uri.scheme?.lowercase() != "http" || uri.host == null) {
            return respond(400, "Bad Request")
        }
        val dest = Destination(uri.host.removeSurrounding("[", "]"), if (uri.port == -1) 80 else uri.port)
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")

        val upstream = try {
            relay.connect(dest)
        } catch (e: Exception) {
            relay.log("HTTP $dest failed: ${e.message}")
            return respond(502, "Bad Gateway")
        }
        try {
            val request = StringBuilder("$method $path $version\r\n")
            for (h in headers) {
                val name = h.substringBefore(':').trim().lowercase()
                if (name in HOP_BY_HOP || name.startsWith("proxy-")) continue
                request.append(h).append("\r\n")
            }
            request.append("Connection: close\r\n\r\n")
            upstream.getOutputStream().apply { write(request.toString().toByteArray(Charsets.ISO_8859_1)); flush() }
            relay.stats.bytesUp.addAndGet(request.length.toLong())
            relay.bridge(client, input, upstream)
        } finally {
            relay.untrack(upstream)
            RelayServer.closeQuietly(upstream)
        }
    }

    private fun local(method: String, path: String) {
        if (method == "GET" && path.substringBefore('?') == "/status") {
            val body = relay.statusJson().toByteArray()
            output.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nCache-Control: no-store\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
            )
            output.write(body)
            output.flush()
        } else {
            respond(404, "Not Found")
        }
    }

    private fun respond(code: Int, reason: String) {
        val body = "$code $reason\n".toByteArray()
        output.write("HTTP/1.1 $code $reason\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(body)
        output.flush()
    }

    /** Reads the request line and headers, with size limits. Null on EOF or oversize. */
    private fun readHead(): List<String>? {
        val lines = ArrayList<String>()
        val line = ByteArrayOutputStream(256)
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (++total > MAX_HEAD_BYTES) return null
            if (b == '\n'.code) {
                val text = line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
                line.reset()
                if (text.isEmpty()) return lines.ifEmpty { null }
                lines += text
                if (lines.size > MAX_HEADERS) return null
            } else {
                line.write(b)
            }
        }
    }

    companion object {
        const val MAX_HEAD_BYTES = 64 * 1024
        const val MAX_HEADERS = 128
        private val HOP_BY_HOP = setOf("connection", "keep-alive", "te", "trailer", "upgrade", "proxy-connection")

        fun parseAuthority(authority: String, defaultPort: Int): Destination? {
            val host: String
            val port: Int
            if (authority.startsWith("[")) {
                val end = authority.indexOf(']')
                if (end < 0) return null
                host = authority.substring(1, end)
                port = authority.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            } else {
                host = authority.substringBeforeLast(':')
                port = if (authority.contains(':')) authority.substringAfterLast(':').toIntOrNull() ?: return null else defaultPort
            }
            if (host.isEmpty() || port !in 1..65535) return null
            return Destination(host, port)
        }
    }
}
