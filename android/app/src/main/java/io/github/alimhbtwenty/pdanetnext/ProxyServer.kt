package io.github.alimhbtwenty.pdanetnext

import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * PdaNet-Next Core USB Proxy Engine
 *
 * Listens on 127.0.0.1:8080 (bridged to Windows 11 via adb reverse).
 * Handles HTTP GET/POST and HTTPS CONNECT tunneling.
 * Packets originate directly from this Android socket stack, ensuring
 * native cellular TTL (64) is observed by carrier DPI rather than laptop's decremented TTL.
 */
class ProxyServer(private val port: Int = 8080) {

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val bytesTransferred = AtomicLong(0)
    val activeConnections = AtomicLong(0)

    fun start(onStatusChange: (Boolean, String) -> Unit) {
        if (isRunning.get()) return

        scope.launch {
            try {
                serverSocket = ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))
                isRunning.set(true)
                withContext(Dispatchers.Main) {
                    onStatusChange(true, "Proxy active on 127.0.0.1:$port")
                }

                while (isRunning.get() && serverSocket?.isClosed == false) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        scope.launch { handleClient(client) }
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                    }
                }
            } catch (e: Exception) {
                Log.e("ProxyServer", "Server failed to start", e)
                withContext(Dispatchers.Main) {
                    onStatusChange(false, "Failed to bind port: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        scope.cancel()
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        activeConnections.incrementAndGet()
        try {
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()

            // Read the initial request line (e.g. CONNECT host:443 HTTP/1.1 or GET http://...)
            val headerLines = mutableListOf<String>()
            val lineBuffer = StringBuilder()
            var prevByte = -1

            while (true) {
                val b = clientIn.read()
                if (b == -1) break
                if (b == '\n'.code && prevByte == '\r'.code) {
                    val line = lineBuffer.toString().trim()
                    if (line.isEmpty()) break
                    headerLines.add(line)
                    lineBuffer.clear()
                } else if (b != '\r'.code) {
                    lineBuffer.append(b.toChar())
                }
                prevByte = b
            }

            if (headerLines.isEmpty()) {
                client.close()
                return@withContext
            }

            val requestLine = headerLines[0]
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                client.close()
                return@withContext
            }

            val method = parts[0].uppercase()
            val target = parts[1]

            if (method == "CONNECT") {
                // HTTPS Tunneling
                handleHttpsConnect(target, client, clientIn, clientOut)
            } else {
                // Plain HTTP forwarding
                handleHttp(target, method, headerLines, client, clientIn, clientOut)
            }
        } catch (e: Exception) {
            Log.d("ProxyServer", "Connection error: ${e.message}")
        } finally {
            activeConnections.decrementAndGet()
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleHttpsConnect(target: String, client: Socket, clientIn: InputStream, clientOut: OutputStream) {
        val hostPort = target.split(":")
        val host = hostPort[0]
        val port = if (hostPort.size > 1) hostPort[1].toIntOrNull() ?: 443 else 443

        val targetSocket = Socket(host, port)
        val targetIn = targetSocket.getInputStream()
        val targetOut = targetSocket.getOutputStream()

        // Send 200 Connection Established to Windows client
        val established = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()
        clientOut.write(established)
        clientOut.flush()

        // Bidirectional stream pipe
        val job1 = scope.launch(Dispatchers.IO) { pipe(clientIn, targetOut) }
        val job2 = scope.launch(Dispatchers.IO) { pipe(targetIn, clientOut) }

        runBlocking {
            joinAll(job1, job2)
            try { targetSocket.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleHttp(target: String, method: String, headers: List<String>, client: Socket, clientIn: InputStream, clientOut: OutputStream) {
        val url = if (target.startsWith("http://") || target.startsWith("https://")) URL(target) else URL("http://$target")
        val host = url.host
        val port = if (url.port != -1) url.port else 80

        val targetSocket = Socket(host, port)
        val targetIn = targetSocket.getInputStream()
        val targetOut = targetSocket.getOutputStream()

        // Send cleaned request to remote host
        val path = if (url.file.isNullOrEmpty()) "/" else url.file
        targetOut.write("$method $path HTTP/1.1\r\n".toByteArray())
        for (h in headers.drop(1)) {
            // Strip proxy-specific headers
            if (!h.startsWith("Proxy-", ignoreCase = true)) {
                targetOut.write("$h\r\n".toByteArray())
            }
        }
        targetOut.write("\r\n".toByteArray())
        targetOut.flush()

        val job1 = scope.launch(Dispatchers.IO) { pipe(clientIn, targetOut) }
        val job2 = scope.launch(Dispatchers.IO) { pipe(targetIn, clientOut) }

        runBlocking {
            joinAll(job1, job2)
            try { targetSocket.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16384)
        try {
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                output.flush()
                bytesTransferred.addAndGet(read.toLong())
            }
        } catch (_: Exception) {}
    }
}
