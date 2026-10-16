package io.github.alimhbtwenty.pdanext.core.xray

import io.github.alimhbtwenty.pdanext.core.router.BypassPolicy
import io.github.alimhbtwenty.pdanext.core.router.DatagramFlow
import io.github.alimhbtwenty.pdanext.core.router.StreamConnection
import io.github.alimhbtwenty.pdanext.core.router.Upstream
import java.io.IOException
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XrayControlTest {

    private class FakeEngine : XrayEngine {
        var runningConfig: String? = null
        var stopCount = 0
        var delayToReturn: Long? = 42L

        override fun start(configJson: String) {
            if (configJson == "INVALID") throw IllegalArgumentException("invalid config format")
            runningConfig = configJson
        }

        override fun stop() {
            runningConfig = null
            stopCount++
        }

        override val isRunning: Boolean get() = runningConfig != null

        override val tunnel: Upstream = object : Upstream {
            override fun connectTcp(dest: InetAddress, port: Int, timeoutMs: Int): StreamConnection {
                throw IOException("fake tunnel")
            }
            override fun openUdp(dest: InetAddress, port: Int): DatagramFlow {
                throw IOException("fake tunnel")
            }
            override fun resolveDns(query: ByteArray): ByteArray? = null
        }

        override fun importLinks(text: String): List<XrayProfile> {
            val list = mutableListOf<XrayProfile>()
            for (line in text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
                if (line.startsWith("vless://") || line.startsWith("vmess://")) {
                    val name = line.substringAfter('#', "Server")
                    list.add(XrayProfile(name, "{\"mock\":\"config for $name\"}"))
                }
            }
            return list
        }

        override fun measureDelay(configJson: String, url: String, timeoutMs: Int): Long? = delayToReturn
    }

    @Test
    fun `store adds, lists, gets, and deletes profiles`() {
        val store = InMemoryXrayProfileStore()
        val p1 = store.add("Server 1", "{\"outbounds\":[1]}")
        val p2 = store.add("Server 2", "{\"outbounds\":[2]}")

        assertEquals(2, store.list().size)
        assertEquals(p1, store.get(p1.id))
        assertEquals(p2, store.get(p2.id))
        assertFalse(p1.toString().contains("{\"outbounds\":[1]}"), "toString must not leak config")

        store.selectedId = p1.id
        assertEquals(p1.id, store.selectedId)

        assertTrue(store.delete(p1.id))
        assertNull(store.selectedId, "deleting selected profile must clear selectedId")
        assertEquals(1, store.list().size)
        assertNull(store.get(p1.id))
    }

    @Test
    fun `control starts and stops engine on select`() {
        val store = InMemoryXrayProfileStore()
        val engine = FakeEngine()
        val bypass = BypassPolicy()
        val control = XrayControl(store, engine, bypass)

        val p1 = store.add("S1", "{\"cfg\":1}")
        assertNull(control.selectedName)
        assertFalse(control.isRunning)
        assertNull(control.activeTunnel)

        control.select(p1.id)
        assertTrue(control.isRunning)
        assertEquals("S1", control.selectedName)
        assertEquals("{\"cfg\":1}", engine.runningConfig)
        assertEquals(engine.tunnel, control.activeTunnel)

        control.select(null)
        assertFalse(control.isRunning)
        assertNull(control.selectedName)
        assertNull(control.activeTunnel)
    }

    @Test
    fun `control handles unknown profile or bad config on select`() {
        val store = InMemoryXrayProfileStore()
        val engine = FakeEngine()
        val control = XrayControl(store, engine)

        assertFailsWith<IllegalArgumentException> { control.select("non-existent-id") }

        val bad = store.add("Bad", "INVALID")
        assertFailsWith<IllegalArgumentException> { control.select(bad.id) }
        assertFalse(control.isRunning)
        assertNull(store.selectedId)
    }

    @Test
    fun `control importLinks adds profiles and returns ids`() {
        val store = InMemoryXrayProfileStore()
        val engine = FakeEngine()
        val control = XrayControl(store, engine)

        val links = "vless://user@host:443#Europe\nvmess://user@host:443#Asia\ninvalid-line"
        val ids = control.importLinks(links)
        assertEquals(2, ids.size)
        assertEquals(2, store.list().size)
        assertEquals("Europe", store.get(ids[0])?.name)
        assertEquals("Asia", store.get(ids[1])?.name)
    }

    @Test
    fun `control measureDelay delegates to engine`() {
        val store = InMemoryXrayProfileStore()
        val engine = FakeEngine()
        val control = XrayControl(store, engine)

        val p = store.add("S1", "{\"cfg\":1}")
        assertEquals(42L, control.measureDelay(p.id))
        assertNull(control.measureDelay("unknown-id"))
    }

    @Test
    fun `control updates bypass domains in store and policy`() {
        val store = InMemoryXrayProfileStore()
        val engine = FakeEngine()
        val bypass = BypassPolicy()
        val control = XrayControl(store, engine, bypass)

        assertFalse(bypass.isDomesticName("example.com"))

        control.setBypassDomains(listOf("example.com", "digikala.com"))
        assertEquals(listOf("example.com", "digikala.com"), control.getBypassDomains())
        assertTrue(bypass.isDomesticName("example.com"))
        assertTrue(bypass.isDomesticName("api.example.com"))
        assertTrue(bypass.isDomesticName("digikala.com"))
        assertFalse(bypass.isDomesticName("google.com"))
    }
}
