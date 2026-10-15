package io.github.alimhbtwenty.pdanext.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogSystemTest {

    @Test
    fun `ring buffer stores last 2000 entries and drops oldest`() {
        val buffer = LogRingBuffer(2000)
        for (i in 0 until 2500) {
            buffer.log(LogLevel.INFO, LogSource.ROUTER, "message $i")
        }
        val dumped = buffer.dump()
        assertEquals(2000, dumped.size)
        assertEquals("message 500", dumped.first().message)
        assertEquals("message 2499", dumped.last().message)
    }

    @Test
    fun `ring buffer preserves chronological order and formats correctly`() {
        val buffer = LogRingBuffer(10)
        buffer.log(LogLevel.INFO, LogSource.ROUTER, "link up")
        buffer.log(LogLevel.WARN, LogSource.DNS, "dns failure")
        buffer.log(LogLevel.ERROR, LogSource.SERVICE, "service crash")

        val dumped = buffer.dump()
        assertEquals(3, dumped.size)
        assertEquals(LogLevel.INFO, dumped[0].level)
        assertEquals(LogSource.ROUTER, dumped[0].source)
        assertEquals("link up", dumped[0].message)

        val text = buffer.dumpText()
        assertTrue(text.contains("[INFO] [router] link up"))
        assertTrue(text.contains("[WARN] [dns] dns failure"))
        assertTrue(text.contains("[ERROR] [service] service crash"))
    }

    @Test
    fun `ring buffer is thread-safe under concurrent writes`() {
        val buffer = LogRingBuffer(2000)
        val pool = Executors.newFixedThreadPool(8)
        val threads = 8
        val perThread = 400

        for (t in 0 until threads) {
            pool.execute {
                for (i in 0 until perThread) {
                    buffer.log(LogLevel.INFO, LogSource.ROUTER, "t$t msg$i")
                }
            }
        }

        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(2000, buffer.size())
        assertEquals(2000, buffer.dump().size)
    }

    @Test
    fun `proves a line containing vless or a UUID is redacted`() {
        val vlessLine = "Connecting to vless://43e8c9b2-1111-2222-3333-444455556666@example.com:443?security=reality"
        val redactedVless = SecretRedactor.redact(vlessLine)
        assertFalse(redactedVless.contains("vless://43e8c9b2"))
        assertTrue(redactedVless.contains("vless://[REDACTED]"))

        val uuidLine = "User session id: 12345678-abcd-ef01-2345-6789abcdef01 active"
        val redactedUuid = SecretRedactor.redact(uuidLine)
        assertFalse(redactedUuid.contains("12345678-abcd-ef01-2345-6789abcdef01"))
        assertTrue(redactedUuid.contains("[REDACTED-UUID]"))

        val buffer = LogRingBuffer(10)
        buffer.log(LogLevel.INFO, LogSource.SERVICE, vlessLine)
        buffer.log(LogLevel.INFO, LogSource.SERVICE, uuidLine)
        val text = buffer.dumpText()

        assertFalse(text.contains("vless://43e8c9b2"))
        assertFalse(text.contains("12345678-abcd-ef01-2345-6789abcdef01"))
        assertTrue(text.contains("vless://[REDACTED]"))
        assertTrue(text.contains("[REDACTED-UUID]"))
    }

    @Test
    fun `redacts passwords and credentials in URLs`() {
        val credUrl = "Fetching config from https://admin:mySecretPassword123@config.domain.com/vpn"
        val redactedUrl = SecretRedactor.redact(credUrl)
        assertFalse(redactedUrl.contains("mySecretPassword123"))
        assertFalse(redactedUrl.contains("admin:"))

        val passLine = "User login password=SuperSecretPassword token=xyz123"
        val redactedPass = SecretRedactor.redact(passLine)
        assertFalse(redactedPass.contains("SuperSecretPassword"))
        assertFalse(redactedPass.contains("xyz123"))
    }

    @Test
    fun `RelayServer serves GET log as text plain on loopback`() {
        val server = RelayServer(port = 0)
        server.start()
        val assignedPort = server.port
        val actualPort = (server.javaClass.getDeclaredField("serverSocket").apply { isAccessible = true }.get(server) as java.net.ServerSocket).localPort
        try {
            server.logBuffer.log(LogLevel.INFO, LogSource.LINK, "test-link-up")
            server.logBuffer.log(LogLevel.WARN, LogSource.DNS, "test-dns-failure")

            val socket = Socket("127.0.0.1", actualPort)
            socket.getOutputStream().write("GET /log HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val statusLine = reader.readLine()
            assertEquals("HTTP/1.1 200 OK", statusLine)

            val headers = mutableMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val parts = line!!.split(":", limit = 2)
                if (parts.size == 2) headers[parts[0].trim().lowercase()] = parts[1].trim()
            }
            assertTrue(headers["content-type"]?.startsWith("text/plain") == true)

            val body = reader.readText()
            assertTrue(body.contains("[INFO] [link] test-link-up"))
            assertTrue(body.contains("[WARN] [dns] test-dns-failure"))
            socket.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `RotatingFileLogger rotates files when exceeding maxBytes and keeps maxFiles`() {
        val tempDir = File.createTempFile("pdanext-test-logs", "").apply { delete(); mkdirs() }
        try {
            val logger = RotatingFileLogger(tempDir, baseName = "test.log", maxBytes = 150, maxFiles = 2)
            logger.append("Line 1: 0123456789012345678901234567890123456789")
            logger.append("Line 2: 0123456789012345678901234567890123456789")
            logger.append("Line 3: 0123456789012345678901234567890123456789")
            logger.append("Line 4: 0123456789012345678901234567890123456789")

            val files = logger.listLogFiles()
            assertTrue(files.size <= 2)
            assertTrue(File(tempDir, "test.log").exists())
            assertTrue(File(tempDir, "test.log.1").exists())
            assertFalse(File(tempDir, "test.log.2").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
