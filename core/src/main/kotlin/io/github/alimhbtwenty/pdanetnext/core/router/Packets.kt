package io.github.alimhbtwenty.pdanetnext.core.router

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

const val PROTO_ICMP = 1
const val PROTO_TCP = 6
const val PROTO_UDP = 17
const val PROTO_ICMPV6 = 58

const val TCP_FIN = 0x01
const val TCP_SYN = 0x02
const val TCP_RST = 0x04
const val TCP_PSH = 0x08
const val TCP_ACK = 0x10

/** A parsed IPv4 or IPv6 packet. Offsets point into [buf]; nothing is copied. */
class IpPacket private constructor(
    val buf: ByteArray,
    val length: Int,
    val version: Int,
    val src: InetAddress,
    val dst: InetAddress,
    val protocol: Int,
    /** Offset of the transport header. */
    val l4Offset: Int,
    val l4Length: Int,
    val ttl: Int,
) {
    val isV6 get() = version == 6

    // --- TCP / UDP fields (valid when protocol matches) ---
    val srcPort get() = u16(buf, l4Offset)
    val dstPort get() = u16(buf, l4Offset + 2)

    val tcpSeq get() = u32(buf, l4Offset + 4)
    val tcpAck get() = u32(buf, l4Offset + 8)
    val tcpHeaderLength get() = ((buf[l4Offset + 12].toInt() ushr 4) and 0xF) * 4
    val tcpFlags get() = buf[l4Offset + 13].toInt() and 0x3F
    val tcpWindow get() = u16(buf, l4Offset + 14)
    val tcpDataOffset get() = l4Offset + tcpHeaderLength
    val tcpDataLength get() = l4Length - tcpHeaderLength

    /** MSS option from a SYN, or null. */
    fun tcpMss(): Int? {
        var i = l4Offset + 20
        val end = l4Offset + tcpHeaderLength
        while (i < end) {
            when (val kind = buf[i].toInt() and 0xFF) {
                0 -> return null
                1 -> i++
                else -> {
                    if (i + 1 >= end) return null
                    val len = buf[i + 1].toInt() and 0xFF
                    if (len < 2) return null
                    if (kind == 2 && len == 4) return u16(buf, i + 2)
                    i += len
                }
            }
        }
        return null
    }

    val udpDataOffset get() = l4Offset + 8
    val udpDataLength get() = l4Length - 8

    // --- ICMP echo fields ---
    val icmpType get() = buf[l4Offset].toInt() and 0xFF
    val icmpCode get() = buf[l4Offset + 1].toInt() and 0xFF
    val icmpId get() = u16(buf, l4Offset + 4)
    val icmpSeq get() = u16(buf, l4Offset + 6)
    val icmpDataOffset get() = l4Offset + 8
    val icmpDataLength get() = l4Length - 8

    fun copyOf(offset: Int, len: Int): ByteArray = buf.copyOfRange(offset, offset + len)

    companion object {
        /** Returns null for anything we do not route: fragments, IPv6 extension headers, truncated packets. */
        fun parse(buf: ByteArray, length: Int): IpPacket? {
            if (length < 20) return null
            return when (buf[0].toInt() ushr 4) {
                4 -> {
                    val ihl = (buf[0].toInt() and 0xF) * 4
                    val total = u16(buf, 2)
                    if (ihl < 20 || total > length || total < ihl) return null
                    val fragment = u16(buf, 6)
                    // More-fragments flag or a non-zero offset: not supported (we use a large MTU).
                    if (fragment and 0x3FFF != 0) return null
                    val proto = buf[9].toInt() and 0xFF
                    val p = IpPacket(
                        buf, total, 4,
                        InetAddress.getByAddress(buf.copyOfRange(12, 16)),
                        InetAddress.getByAddress(buf.copyOfRange(16, 20)),
                        proto, ihl, total - ihl, buf[8].toInt() and 0xFF,
                    )
                    if (p.validL4()) p else null
                }
                6 -> {
                    if (length < 40) return null
                    val payload = u16(buf, 4)
                    if (40 + payload > length) return null
                    val p = IpPacket(
                        buf, 40 + payload, 6,
                        InetAddress.getByAddress(buf.copyOfRange(8, 24)),
                        InetAddress.getByAddress(buf.copyOfRange(24, 40)),
                        buf[6].toInt() and 0xFF, 40, payload, buf[7].toInt() and 0xFF,
                    )
                    if (p.validL4()) p else null
                }
                else -> null
            }
        }

        fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        fun u32(b: ByteArray, i: Int): Long =
            ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
                ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)
    }

    private fun validL4(): Boolean = when (protocol) {
        PROTO_TCP -> l4Length >= 20 && tcpHeaderLength in 20..l4Length
        PROTO_UDP -> l4Length >= 8 && u16(buf, l4Offset + 4) <= l4Length
        PROTO_ICMP, PROTO_ICMPV6 -> l4Length >= 8
        else -> true
    }
}

/** Builds complete IP packets with correct checksums. */
object PacketBuilder {
    private val ipId = AtomicInteger()
    const val DEFAULT_TTL = 64

    fun tcp(
        src: InetAddress, srcPort: Int, dst: InetAddress, dstPort: Int,
        seq: Long, ack: Long, flags: Int, window: Int,
        data: ByteArray? = null, dataOffset: Int = 0, dataLength: Int = 0,
        mss: Int? = null,
    ): ByteArray {
        val optLen = if (mss != null) 4 else 0
        val hdr = 20 + optLen
        val l4 = ByteArray(hdr + dataLength)
        put16(l4, 0, srcPort)
        put16(l4, 2, dstPort)
        put32(l4, 4, seq)
        put32(l4, 8, ack)
        l4[12] = ((hdr / 4) shl 4).toByte()
        l4[13] = flags.toByte()
        put16(l4, 14, window.coerceIn(0, 0xFFFF))
        if (mss != null) {
            l4[20] = 2; l4[21] = 4; put16(l4, 22, mss)
        }
        if (dataLength > 0) System.arraycopy(data!!, dataOffset, l4, hdr, dataLength)
        put16(l4, 16, transportChecksum(src, dst, PROTO_TCP, l4))
        return ip(src, dst, PROTO_TCP, l4)
    }

    fun udp(src: InetAddress, srcPort: Int, dst: InetAddress, dstPort: Int, data: ByteArray, offset: Int, length: Int): ByteArray {
        val l4 = ByteArray(8 + length)
        put16(l4, 0, srcPort)
        put16(l4, 2, dstPort)
        put16(l4, 4, l4.size)
        System.arraycopy(data, offset, l4, 8, length)
        var sum = transportChecksum(src, dst, PROTO_UDP, l4)
        if (sum == 0) sum = 0xFFFF
        put16(l4, 6, sum)
        return ip(src, dst, PROTO_UDP, l4)
    }

    /** ICMP / ICMPv6 echo request (type 8 / 128) or reply (type 0 / 129). */
    fun icmpEcho(src: InetAddress, dst: InetAddress, reply: Boolean, id: Int, seq: Int, payload: ByteArray, ttl: Int = DEFAULT_TTL): ByteArray {
        val v6 = src is Inet6Address
        val l4 = ByteArray(8 + payload.size)
        l4[0] = (if (v6) (if (reply) 129 else 128) else (if (reply) 0 else 8)).toByte()
        put16(l4, 4, id)
        put16(l4, 6, seq)
        payload.copyInto(l4, 8)
        val proto = if (v6) PROTO_ICMPV6 else PROTO_ICMP
        put16(l4, 2, if (v6) transportChecksum(src, dst, proto, l4) else finish(sum(l4, 0, l4.size, 0)))
        return ip(src, dst, proto, l4, ttl)
    }

    /** "Destination unreachable" from [from] (same family as [original]), so the PC app fails fast. */
    fun unreachable(from: InetAddress, original: IpPacket): ByteArray {
        val v6 = original.isV6
        val quote = original.buf.copyOfRange(0, minOf(original.length, if (v6) 1232 else 548))
        val l4 = ByteArray(8 + quote.size)
        // v4: type 3 code 1 (host unreachable); v6: type 1 code 3 (address unreachable).
        l4[0] = (if (v6) 1 else 3).toByte()
        l4[1] = (if (v6) 3 else 1).toByte()
        quote.copyInto(l4, 8)
        val proto = if (v6) PROTO_ICMPV6 else PROTO_ICMP
        put16(l4, 2, if (v6) transportChecksum(from, original.src, proto, l4) else finish(sum(l4, 0, l4.size, 0)))
        return ip(from, original.src, proto, l4)
    }

    fun ip(src: InetAddress, dst: InetAddress, proto: Int, l4: ByteArray, ttl: Int = DEFAULT_TTL): ByteArray {
        val s = src.address
        val d = dst.address
        return if (src is Inet4Address) {
            val p = ByteArray(20 + l4.size)
            p[0] = 0x45
            put16(p, 2, p.size)
            put16(p, 4, ipId.incrementAndGet() and 0xFFFF)
            put16(p, 6, 0x4000) // don't fragment
            p[8] = ttl.toByte()
            p[9] = proto.toByte()
            s.copyInto(p, 12)
            d.copyInto(p, 16)
            put16(p, 10, finish(sum(p, 0, 20, 0)))
            l4.copyInto(p, 20)
            p
        } else {
            val p = ByteArray(40 + l4.size)
            p[0] = 0x60
            put16(p, 4, l4.size)
            p[6] = proto.toByte()
            p[7] = ttl.toByte()
            s.copyInto(p, 8)
            d.copyInto(p, 24)
            l4.copyInto(p, 40)
            p
        }
    }

    fun transportChecksum(src: InetAddress, dst: InetAddress, proto: Int, l4: ByteArray): Int {
        val s = src.address
        val d = dst.address
        var acc = sum(s, 0, s.size, 0)
        acc = sum(d, 0, d.size, acc)
        acc += proto
        acc += l4.size // fits in 32 bits for IPv6 jumbo-free packets
        acc = sum(l4, 0, l4.size, acc)
        return finish(acc)
    }

    /** Verifies a received packet's transport checksum (used by tests). */
    fun verify(p: IpPacket): Boolean {
        val l4 = p.copyOf(p.l4Offset, p.l4Length)
        if (p.protocol == PROTO_ICMP) return finish(sum(l4, 0, l4.size, 0)) == 0
        if (p.protocol == PROTO_UDP && IpPacket.u16(l4, 6) == 0) return true
        return transportChecksum(p.src, p.dst, p.protocol, l4) == 0
    }

    private fun sum(b: ByteArray, off: Int, len: Int, initial: Long): Long {
        var acc = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            acc += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) acc += (b[i].toInt() and 0xFF) shl 8
        return acc
    }

    private fun sum(b: ByteArray, off: Int, len: Int, initial: Int): Long = sum(b, off, len, initial.toLong())

    private fun finish(acc: Long): Int {
        var a = acc
        while (a ushr 16 != 0L) a = (a and 0xFFFF) + (a ushr 16)
        return (a.inv() and 0xFFFF).toInt()
    }

    private fun finish(acc: Int): Int = finish(acc.toLong())

    fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte()
    }

    fun put32(b: ByteArray, i: Int, v: Long) {
        b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte(); b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte()
    }
}
