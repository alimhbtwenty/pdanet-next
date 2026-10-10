package io.github.alimhbtwenty.pdanetnext.core

import java.net.InetAddress

/**
 * Runs the relay on a desktop JVM, for testing without a phone:
 * `gradle -p core installDist && core/build/install/core/bin/core --bind 127.0.0.1 --port 28080`
 */
fun main(args: Array<String>) {
    fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val relay = RelayServer(
        port = arg("--port")?.toInt() ?: RelayServer.DEFAULT_PORT,
        bindAddress = InetAddress.getByName(arg("--bind") ?: "127.0.0.1"),
        log = { println(it) },
    )
    relay.start()
    Runtime.getRuntime().addShutdownHook(Thread { relay.stop() })
    Thread.currentThread().join()
}
