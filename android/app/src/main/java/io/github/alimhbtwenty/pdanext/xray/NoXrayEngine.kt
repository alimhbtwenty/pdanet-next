package io.github.alimhbtwenty.pdanext.xray

import io.github.alimhbtwenty.pdanext.core.router.DatagramFlow
import io.github.alimhbtwenty.pdanext.core.router.StreamConnection
import io.github.alimhbtwenty.pdanext.core.router.Upstream
import io.github.alimhbtwenty.pdanext.core.xray.XrayEngine
import io.github.alimhbtwenty.pdanext.core.xray.XrayProfile
import java.io.IOException
import java.net.InetAddress

/**
 * Placeholder engine until the xray-bridge branch merges.
 * Start throws IllegalStateException("Xray engine is not bundled in this build").
 */
class NoXrayEngine : XrayEngine {
    override fun start(configJson: String) {
        throw IllegalStateException("Xray engine is not bundled in this build")
    }

    override fun stop() {}

    override val isRunning: Boolean get() = false

    override val tunnel: Upstream = object : Upstream {
        override fun connectTcp(dest: InetAddress, port: Int, timeoutMs: Int): StreamConnection {
            throw IOException("Xray engine is not bundled in this build")
        }

        override fun openUdp(dest: InetAddress, port: Int): DatagramFlow {
            throw IOException("Xray engine is not bundled in this build")
        }

        override fun resolveDns(query: ByteArray): ByteArray? = null
    }

    override fun importLinks(text: String): List<XrayProfile> = emptyList()

    override fun measureDelay(configJson: String, url: String, timeoutMs: Int): Long? = null
}
