package io.github.alimhbtwenty.pdanetnext.core

import java.io.DataInputStream
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** SOCKS5 without authentication: CONNECT, plus UDP-over-TCP when the target is the UoT magic name. */
internal class Socks5Session(
    private val relay: RelayServer,
    private val client: Socket,
    private val input: DataInputStream,
) {
    private val output = client.getOutputStream()

    fun run() {
        // Greeting: VER NMETHODS METHODS...
        if (input.readUnsignedByte() != 5) throw ProtocolException("not socks5")
        val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        if (methods.none { it.toInt() == 0x00 }) {
            output.write(byteArrayOf(5, 0xFF.toByte()))
            return
        }
        output.write(byteArrayOf(5, 0))
        output.flush()

        // Request: VER CMD RSV ADDR
        if (input.readUnsignedByte() != 5) throw ProtocolException("bad request version")
        val command = input.readUnsignedByte()
        input.readUnsignedByte()
        val dest = AddressCodec.SOCKS.read(input)

        if (command != CMD_CONNECT) {
            // UDP ASSOCIATE needs a UDP path, which an ADB link does not have: clients use UoT instead.
            reply(REP_COMMAND_NOT_SUPPORTED)
            return
        }

        if (dest.host == UOT_MAGIC_V2) {
            reply(REP_SUCCEEDED)
            UdpOverTcpSession(relay, client, input).run()
            return
        }
        if (dest.host == UOT_MAGIC_LEGACY) {
            reply(REP_COMMAND_NOT_SUPPORTED)
            return
        }

        val target = try {
            relay.connect(dest)
        } catch (e: Exception) {
            relay.log("connect $dest failed: ${e.javaClass.simpleName}: ${e.message}")
            reply(replyCodeFor(e))
            return
        }
        try {
            reply(REP_SUCCEEDED, target.localAddress, target.localPort)
            relay.bridge(client, input, target)
        } finally {
            relay.untrack(target)
            RelayServer.closeQuietly(target)
        }
    }

    private fun reply(code: Int, bound: InetAddress = ANY_V4, port: Int = 0) {
        output.write(byteArrayOf(5, code.toByte(), 0) + AddressCodec.SOCKS.encode(bound, port))
        output.flush()
    }

    private fun replyCodeFor(e: Exception): Int = when (e) {
        is UnknownHostException, is NoRouteToHostException -> REP_HOST_UNREACHABLE
        is ConnectException -> REP_CONNECTION_REFUSED
        is SocketTimeoutException -> REP_TTL_EXPIRED
        else -> REP_GENERAL_FAILURE
    }

    companion object {
        const val UOT_MAGIC_V2 = "sp.v2.udp-over-tcp.arpa"
        const val UOT_MAGIC_LEGACY = "sp.udp-over-tcp.arpa"

        const val CMD_CONNECT = 1
        const val REP_SUCCEEDED = 0
        const val REP_GENERAL_FAILURE = 1
        const val REP_HOST_UNREACHABLE = 4
        const val REP_CONNECTION_REFUSED = 5
        const val REP_TTL_EXPIRED = 6
        const val REP_COMMAND_NOT_SUPPORTED = 7

        private val ANY_V4: InetAddress = Inet4Address.getByAddress(ByteArray(4))
    }
}
