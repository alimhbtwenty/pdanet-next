package io.github.alimhbtwenty.pdanetnext.core

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress

/** A host (IP literal or domain name) and port, as carried by SOCKS5 and UoT headers. */
data class Destination(val host: String, val port: Int, val ip: InetAddress? = null) {
    override fun toString(): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

/**
 * Address codecs. SOCKS5 uses ATYP 1/3/4 for IPv4/domain/IPv6; sing-box's UDP-over-TCP
 * packet header uses 0/2/1 for the same families. Both are "type, address, big-endian port".
 */
enum class AddressCodec(val ipv4: Int, val ipv6: Int, val domain: Int) {
    SOCKS(0x01, 0x04, 0x03),
    UOT(0x00, 0x01, 0x02);

    fun read(input: DataInputStream): Destination {
        val type = input.readUnsignedByte()
        val dest = when (type) {
            ipv4 -> {
                val raw = ByteArray(4).also { input.readFully(it) }
                val ip = InetAddress.getByAddress(raw)
                Destination(ip.hostAddress!!, 0, ip)
            }
            ipv6 -> {
                val raw = ByteArray(16).also { input.readFully(it) }
                val ip = InetAddress.getByAddress(raw)
                Destination(ip.hostAddress!!, 0, ip)
            }
            domain -> {
                val len = input.readUnsignedByte()
                val raw = ByteArray(len).also { input.readFully(it) }
                Destination(String(raw, Charsets.US_ASCII), 0)
            }
            else -> throw ProtocolException("unknown address type $type")
        }
        return dest.copy(port = input.readUnsignedShort())
    }

    fun encode(ip: InetAddress, port: Int): ByteArray {
        val raw = ip.address
        val out = ByteArray(1 + raw.size + 2)
        out[0] = (if (ip is Inet4Address) ipv4 else ipv6).toByte()
        raw.copyInto(out, 1)
        out[out.size - 2] = (port ushr 8).toByte()
        out[out.size - 1] = port.toByte()
        return out
    }

    fun write(output: OutputStream, ip: InetAddress, port: Int) = output.write(encode(ip, port))
}

class ProtocolException(message: String) : IOException(message)
