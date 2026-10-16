package io.github.alimhbtwenty.pdanext.core.xray

import io.github.alimhbtwenty.pdanext.core.router.Upstream

/**
 * The contract between the router (pure JVM, `core/`) and the embedded Xray engine (Android, a
 * gomobile AAR). See ADR-0001. The Android app implements it on top of the `xraybridge` Go module;
 * tests and the desktop relay use fakes.
 *
 * Rules every implementation keeps:
 * - Xray runs **in-process with outbounds only**: no inbound, no listener, no proxy, no TUN.
 * - Config JSON, share links, UUIDs and keys are secrets: never log them, never put them in
 *   exceptions or the status JSON.
 * - Thread-safe: the router calls [tunnel] from many threads while the PC may switch configs.
 */
interface XrayEngine {
    /**
     * Starts (or restarts) Xray with a full Xray JSON config whose outbounds carry the traffic.
     * Throws [IllegalArgumentException] for an invalid config; the message must not contain secrets.
     */
    fun start(configJson: String)

    /** Stops Xray. Idempotent. */
    fun stop()

    /** True while an Xray instance is running. */
    val isRunning: Boolean

    /**
     * The tunnel the router uses for Route.TUNNEL flows. Stays the same object for the engine's life:
     * while Xray is stopped its connects throw IOException (no fallback to direct, ADR-0001).
     * `connectTcp`/`openUdp` map to Xray `core.Dial` / `core.DialUDP`; `resolveDns` sends the raw
     * DNS query through the tunnel (UDP to a public resolver via `core.DialUDP`).
     */
    val tunnel: Upstream

    /**
     * Converts share links (vless://, vmess://, trojan://, ss://, one per line) or a base64
     * subscription body into full Xray JSON configs. Unknown lines are skipped.
     */
    fun importLinks(text: String): List<XrayProfile>

    /**
     * Measures the delay through [configJson] (a separate, temporary instance; the running one is
     * not touched) by fetching [url]. Returns milliseconds, or null on failure or timeout.
     */
    fun measureDelay(configJson: String, url: String = DEFAULT_DELAY_URL, timeoutMs: Int = 5000): Long?

    companion object {
        const val DEFAULT_DELAY_URL = "https://www.gstatic.com/generate_204"
    }
}

/** One importable server: a display name and its full Xray JSON config. */
data class XrayProfile(val name: String, val configJson: String) {
    override fun toString(): String = "XrayProfile(name=$name)" // never print the config
}
