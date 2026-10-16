package io.github.alimhbtwenty.pdanext.core.xray

import io.github.alimhbtwenty.pdanext.core.RelayServer
import io.github.alimhbtwenty.pdanext.core.router.BypassPolicy
import io.github.alimhbtwenty.pdanext.core.router.DatagramFlow
import io.github.alimhbtwenty.pdanext.core.router.StreamConnection
import io.github.alimhbtwenty.pdanext.core.router.Upstream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XrayEndpointsTest {

    private class TestEngine : XrayEngine {
        var currentConfig: String? = null
        override fun start(configJson: String) { currentConfig = configJson }
        override fun stop() { currentConfig = null }
        override val isRunning: Boolean get() = currentConfig != null
        override val tunnel: Upstream = object : Upstream {
            override fun connectTcp(dest: InetAddress, port: Int, timeoutMs: Int): StreamConnection = throw IOException("test")
            override fun openUdp(dest: InetAddress, port: Int): DatagramFlow = throw IOException("test")
            override fun resolveDns(query: ByteArray): ByteArray? = null
        }
        override fun importLinks(text: String): List<XrayProfile> {
            return text.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("vless://") || it.startsWith("vmess://") }
                .map { line ->
                    val name = line.substringAfter('#', "Imported")
                    XrayProfile(name, "{\"secret_uuid\":\"should-never-leak-12345\",\"name\":\"$name\"}")
                }.toList()
        }
        override fun measureDelay(configJson: String, url: String, timeoutMs: Int): Long? = 55L
    }

    private lateinit var store: InMemoryXrayProfileStore
    private lateinit var engine: TestEngine
    private lateinit var bypassPolicy: BypassPolicy
    private lateinit var control: XrayControl
    private lateinit var relay: RelayServer

    @BeforeTest
    fun setUp() {
        store = InMemoryXrayProfileStore()
        engine = TestEngine()
        bypassPolicy = BypassPolicy()
        control = XrayControl(store, engine, bypassPolicy)
        val port = ServerSocket(0).use { it.localPort }
        relay = RelayServer(port = port, xrayControl = control, bypassPolicy = bypassPolicy)
        relay.start()
    }

    @AfterTest
    fun tearDown() {
        relay.stop()
    }

    private fun request(method: String, path: String, body: String? = null): String {
        return Socket(InetAddress.getLoopbackAddress(), relay.port).use { s ->
            val out = s.getOutputStream()
            val bodyBytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val header = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: phone\r\n")
                append("Connection: close\r\n")
                if (method == "POST") {
                    append("Content-Length: ${bodyBytes.size}\r\n")
                    append("Content-Type: application/json\r\n")
                }
                append("\r\n")
            }
            out.write(header.toByteArray(Charsets.US_ASCII))
            if (bodyBytes.isNotEmpty()) {
                out.write(bodyBytes)
            }
            out.flush()
            String(s.getInputStream().readBytes())
        }
    }

    @Test
    fun `profiles endpoint returns empty list then populated list with no secrets`() {
        val res1 = request("GET", "/xray/profiles")
        assertTrue(res1.startsWith("HTTP/1.1 200 OK"), res1)
        assertTrue(res1.endsWith("[]"), res1)

        val p1 = store.add("Server A", "{\"password\":\"SECRET_PASSWORD_123\"}")
        val res2 = request("GET", "/xray/profiles")
        assertTrue(res2.startsWith("HTTP/1.1 200 OK"), res2)
        assertTrue("\"id\":\"${p1.id}\"" in res2, res2)
        assertTrue("\"name\":\"Server A\"" in res2, res2)
        assertTrue("\"selected\":false" in res2, res2)
        assertFalse("SECRET_PASSWORD_123" in res2, "profile endpoint must never leak passwords or config")

        control.select(p1.id)
        val res3 = request("GET", "/xray/profiles")
        assertTrue("\"selected\":true" in res3, res3)
    }

    @Test
    fun `import endpoint accepts links text and returns added ids without leaking config`() {
        val links = "vless://user:pass@host:443#PrimaryServer\nvmess://user:pass@host:443#BackupServer"
        val res = request("POST", "/xray/import", links)
        assertTrue(res.startsWith("HTTP/1.1 200 OK"), res)
        assertFalse("should-never-leak-12345" in res, "import must not leak secret uuid")
        assertFalse("pass" in res, "import must not leak link passwords")

        val profiles = store.list()
        assertEquals(2, profiles.size)
        assertEquals("PrimaryServer", profiles[0].name)
        assertEquals("BackupServer", profiles[1].name)
    }

    @Test
    fun `select endpoint selects server or null off and updates status`() {
        val p = store.add("TestServer", "{\"cfg\":\"val\"}")

        val selRes = request("POST", "/xray/select", "{\"id\":\"${p.id}\"}")
        assertTrue(selRes.startsWith("HTTP/1.1 200 OK"), selRes)
        assertEquals(p.id, control.selectedId)
        assertEquals("TestServer", control.selectedName)

        val statusRes = request("GET", "/status")
        assertTrue("\"xrayProfile\":\"TestServer\"" in statusRes, statusRes)

        val offRes = request("POST", "/xray/select", "{\"id\":null}")
        assertTrue(offRes.startsWith("HTTP/1.1 200 OK"), offRes)
        assertEquals(null, control.selectedId)
        assertEquals(null, control.selectedName)

        val statusOffRes = request("GET", "/status")
        assertTrue("\"xrayProfile\":null" in statusOffRes, statusOffRes)
    }

    @Test
    fun `select endpoint returns 400 on unknown id or bad json`() {
        val badId = request("POST", "/xray/select", "{\"id\":\"does-not-exist\"}")
        assertTrue(badId.startsWith("HTTP/1.1 400 Bad Request"), badId)

        val badJson = request("POST", "/xray/select", "{invalid-json}")
        assertTrue(badJson.startsWith("HTTP/1.1 400 Bad Request"), badJson)
    }

    @Test
    fun `delete endpoint removes profile and clears selection if active`() {
        val p = store.add("ToDelete", "{\"cfg\":1}")
        control.select(p.id)

        val res = request("POST", "/xray/delete", "{\"id\":\"${p.id}\"}")
        assertTrue(res.startsWith("HTTP/1.1 200 OK"), res)
        assertTrue("{\"ok\":true}" in res, res)
        assertEquals(null, control.selectedId)
        assertEquals(0, store.list().size)

        val badJson = request("POST", "/xray/delete", "{bad}")
        assertTrue(badJson.startsWith("HTTP/1.1 400 Bad Request"), badJson)
    }

    @Test
    fun `delay endpoint measures latency`() {
        val p = store.add("LatencyServer", "{\"cfg\":1}")
        val res = request("POST", "/xray/delay", "{\"id\":\"${p.id}\"}")
        assertTrue(res.startsWith("HTTP/1.1 200 OK"), res)
        assertTrue("{\"ms\":55}" in res, res)

        val notFound = request("POST", "/xray/delay", "{\"id\":\"unknown\"}")
        assertTrue(notFound.startsWith("HTTP/1.1 400 Bad Request"), notFound)
    }

    @Test
    fun `bypass endpoint gets and sets domain list`() {
        val resGet1 = request("GET", "/xray/bypass")
        assertTrue(resGet1.startsWith("HTTP/1.1 200 OK"), resGet1)
        assertTrue(resGet1.endsWith("[]"), resGet1)

        val resPost = request("POST", "/xray/bypass", "[\"digikala.com\",\"bank.example.ir\"]")
        assertTrue(resPost.startsWith("HTTP/1.1 200 OK"), resPost)
        assertTrue("\"digikala.com\"" in resPost, resPost)

        val resGet2 = request("GET", "/xray/bypass")
        assertTrue("\"digikala.com\"" in resGet2, resGet2)
        assertTrue(bypassPolicy.isDomesticName("digikala.com"))

        val resText = request("POST", "/xray/bypass", "aparat.com\nsite.ir")
        assertTrue(resText.startsWith("HTTP/1.1 200 OK"), resText)
        assertTrue("\"aparat.com\"" in resText, resText)
        assertTrue(bypassPolicy.isDomesticName("aparat.com"))
    }

    @Test
    fun `bad content length or oversized payload returns 400`() {
        val missingLen = Socket(InetAddress.getLoopbackAddress(), relay.port).use { s ->
            s.getOutputStream().write("POST /xray/select HTTP/1.1\r\nHost: phone\r\nConnection: close\r\n\r\n{}".toByteArray())
            String(s.getInputStream().readBytes())
        }
        assertTrue(missingLen.startsWith("HTTP/1.1 400 Bad Request"), missingLen)

        val oversized = Socket(InetAddress.getLoopbackAddress(), relay.port).use { s ->
            s.getOutputStream().write("POST /xray/import HTTP/1.1\r\nHost: phone\r\nContent-Length: 2000000\r\nConnection: close\r\n\r\n".toByteArray())
            String(s.getInputStream().readBytes())
        }
        assertTrue(oversized.startsWith("HTTP/1.1 400 Bad Request"), oversized)
    }

    @Test
    fun `non-loopback requests to xray endpoints receive 403 Forbidden`() {
        // Test loopback security check using direct invocation on serveHttp with non-loopback socket
        val nonLoopbackAddr = InetAddress.getByName("192.168.1.50")
        val fakeSocket = object : Socket() {
            override fun getInetAddress(): InetAddress = nonLoopbackAddr
        }
        val req = "GET /xray/profiles HTTP/1.1\r\nHost: phone\r\n\r\n".toByteArray()
        val inStream = ByteArrayInputStream(req)
        val outStream = ByteArrayOutputStream()

        val method = RelayServer::class.java.getDeclaredMethod(
            "serveHttp", Socket::class.java, java.io.InputStream::class.java, java.io.OutputStream::class.java
        )
        method.isAccessible = true
        method.invoke(relay, fakeSocket, inStream, outStream)

        val response = outStream.toString("UTF-8")
        assertTrue(response.startsWith("HTTP/1.1 403 Forbidden"), response)
    }
}
