package io.github.alimhbtwenty.pdanetnext.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Server side of sing-box "UDP over TCP" version 2 (sagernet/sing common/uot).
 *
 * After the SOCKS5 CONNECT to `sp.v2.udp-over-tcp.arpa` succeeds the stream carries:
 *   request:  isConnect(1 byte) + destination in SOCKS address format
 *   packets:  [address in UoT format, unless isConnect] + length(u16 BE) + payload
 * in both directions. Replies carry the source address the datagram came from.
 */
internal class UdpOverTcpSession(
    private val relay: RelayServer,
    private val client: Socket,
    private val input: DataInputStream,
) {
    private val output = DataOutputStream(client.getOutputStream().buffered(64 * 1024))
    private val udp = DatagramSocket()
    private val resolved = HashMap<String, InetAddress>()
    @Volatile private var lastActivity = System.currentTimeMillis()

    fun run() {
        relay.stats.activeUdp.incrementAndGet()
        try {
            val isConnect = input.readUnsignedByte() != 0
            val requestDest = AddressCodec.SOCKS.read(input)
            client.soTimeout = 0
            val fixedTarget = if (isConnect) resolve(requestDest) else null
            relay.execute { receiveLoop(isConnect) }

            val buffer = ByteArray(65535)
            while (true) {
                val dest = if (isConnect) requestDest else AddressCodec.UOT.read(input)
                val length = input.readUnsignedShort()
                input.readFully(buffer, 0, length)
                lastActivity = System.currentTimeMillis()
                relay.stats.bytesUp.addAndGet(length.toLong())
                try {
                    udp.send(DatagramPacket(buffer, length, fixedTarget ?: resolve(dest)))
                } catch (e: IOException) {
                    // One unresolvable or unreachable destination must not end the whole session.
                    relay.log("udp send to $dest failed: ${e.message}")
                }
            }
        } catch (_: EOFException) {
        } catch (e: IOException) {
            if (!udp.isClosed) relay.log("uot session ended: ${e.message}")
        } finally {
            udp.close()
            relay.stats.activeUdp.decrementAndGet()
        }
    }

    private fun receiveLoop(isConnect: Boolean) {
        val buffer = ByteArray(65535)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            udp.soTimeout = 1000
            while (!udp.isClosed) {
                try {
                    packet.setData(buffer)
                    udp.receive(packet)
                } catch (_: SocketTimeoutException) {
                    if (System.currentTimeMillis() - lastActivity > IDLE_TIMEOUT_MS) break
                    continue
                }
                lastActivity = System.currentTimeMillis()
                relay.stats.bytesDown.addAndGet(packet.length.toLong())
                synchronized(output) {
                    if (!isConnect) AddressCodec.UOT.write(output, packet.address, packet.port)
                    output.writeShort(packet.length)
                    output.write(packet.data, packet.offset, packet.length)
                    output.flush()
                }
            }
        } catch (_: IOException) {
        } finally {
            // Closing the TCP side ends the reader loop in run().
            udp.close()
            RelayServer.closeQuietly(client)
        }
    }

    private fun resolve(dest: Destination): InetSocketAddress {
        val ip = dest.ip ?: resolved.getOrPut(dest.host) { InetAddress.getByName(dest.host) }
        return InetSocketAddress(ip, dest.port)
    }

    companion object {
        const val IDLE_TIMEOUT_MS = 5 * 60_000L
    }
}
