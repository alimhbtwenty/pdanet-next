package io.github.alimhbtwenty.pdanext.core

import io.github.alimhbtwenty.pdanext.core.router.BypassPolicy
import io.github.alimhbtwenty.pdanext.core.router.DatagramFlow
import io.github.alimhbtwenty.pdanext.core.router.Ipv4Ranges
import io.github.alimhbtwenty.pdanext.core.router.MiniDns
import io.github.alimhbtwenty.pdanext.core.router.SocketStreamConnection
import io.github.alimhbtwenty.pdanext.core.router.StreamConnection
import io.github.alimhbtwenty.pdanext.core.router.Upstream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/**
 * Runs the relay on a desktop JVM, for testing without a phone:
 * `gradle -p core installDist && core/build/install/core/bin/core --bind 127.0.0.1 --port 28080`
 */
fun main(args: Array<String>) {
    fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val tunnelPort = arg("--tunnel-port")?.toInt()
    val tunnel = tunnelPort?.let { port ->
        object : Upstream {
            override fun connectTcp(dest: InetAddress, dPort: Int, timeoutMs: Int): StreamConnection {
                val s = Socket()
                s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                s.tcpNoDelay = true
                return SocketStreamConnection(s)
            }
            override fun openUdp(dest: InetAddress, port: Int): DatagramFlow = throw IOException("tunnel udp not implemented")
            override fun resolveDns(query: ByteArray): ByteArray? = MiniDns.answer(query) { host ->
                if (host.equals("foreign.test", ignoreCase = true)) {
                    arrayOf(InetAddress.getByName("203.0.113.20"))
                } else {
                    throw UnknownHostException(host)
                }
            }
        }
    }
    val policy = if (tunnel != null) {
        BypassPolicy(domains = listOf("target.test"), ranges = Ipv4Ranges.parse("203.0.113.10/32"))
    } else {
        BypassPolicy()
    }
    val relay = RelayServer(
        port = arg("--port")?.toInt() ?: RelayServer.DEFAULT_PORT,
        bindAddress = InetAddress.getByName(arg("--bind") ?: "127.0.0.1"),
        log = { println(it) },
        tunnelUpstream = tunnel,
        bypassPolicy = policy,
    )
    relay.start()
    Runtime.getRuntime().addShutdownHook(Thread { relay.stop() })
    Thread.currentThread().join()
}
