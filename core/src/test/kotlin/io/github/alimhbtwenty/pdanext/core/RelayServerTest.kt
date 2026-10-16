package io.github.alimhbtwenty.pdanext.core

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayServerTest {
    private val relay = RelayServer(port = ServerSocket(0).use { it.localPort }, statusExtras = { mapOf("vpnActive" to true) })
        .also { it.start() }

    @AfterTest
    fun tearDown() = relay.stop()

    private fun get(path: String): String = Socket(InetAddress.getLoopbackAddress(), relay.port).use { s ->
        s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: phone\r\n\r\n".toByteArray())
        String(s.getInputStream().readBytes())
    }

    @Test
    fun `status endpoint returns json with extras`() {
        val response = get("/status")
        assertTrue(response.startsWith("HTTP/1.1 200"), response)
        assertTrue("\"app\":\"pdanext\"" in response, response)
        assertTrue("\"vpnActive\":true" in response, response)
        assertTrue("\"routerMode\":false" in response, response)
        assertTrue("\"protected\":true" in response, response)
        assertTrue("\"killSwitch\":false" in response, response)
        assertTrue("\"iranRanges\":0" in response, response)
    }

    @Test
    fun `status endpoint reports iranRanges count`() {
        val server = RelayServer(
            port = ServerSocket(0).use { it.localPort },
            bypassPolicy = io.github.alimhbtwenty.pdanext.core.router.BypassPolicy(
                ranges = io.github.alimhbtwenty.pdanext.core.router.Ipv4Ranges.parse("5.160.0.0/16\n2.144.0.0/14")
            ),
        ).also { it.start() }
        try {
            val response = Socket(InetAddress.getLoopbackAddress(), server.port).use { s ->
                s.getOutputStream().write("GET /status HTTP/1.1\r\nHost: phone\r\n\r\n".toByteArray())
                String(s.getInputStream().readBytes())
            }
            assertTrue("\"iranRanges\":2" in response, response)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `status endpoint reports killSwitch true and protected false when protection is required but inactive`() {
        val server = RelayServer(
            port = ServerSocket(0).use { it.localPort },
            requireProtection = true,
            isProtected = { false },
        ).also { it.start() }
        try {
            val response = Socket(InetAddress.getLoopbackAddress(), server.port).use { s ->
                s.getOutputStream().write("GET /status HTTP/1.1\r\nHost: phone\r\n\r\n".toByteArray())
                String(s.getInputStream().readBytes())
            }
            assertTrue("\"killSwitch\":true" in response, response)
            assertTrue("\"protected\":false" in response, response)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `reported version matches the VERSION file`() {
        val file = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "VERSION") }.first { it.exists() }
        assertEquals(file.readText().trim(), RelayServer.VERSION)
    }

    @Test
    fun `ping-pc without a PC attached says null`() {
        assertTrue(get("/ping-pc").endsWith("{\"ms\":null}"))
    }

    @Test
    fun `unknown paths and protocols are refused`() {
        assertTrue(get("/nope").startsWith("HTTP/1.1 404"))
        Socket(InetAddress.getLoopbackAddress(), relay.port).use { s ->
            // A SOCKS5 greeting: proxy mode no longer exists, the connection is just closed.
            s.getOutputStream().write(byteArrayOf(5, 1, 0, 0))
            assertEquals(-1, s.getInputStream().read())
        }
    }
}
