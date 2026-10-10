package io.github.alimhbtwenty.pdanext.core.router

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * A tiny DNS responder backed by the platform resolver ([InetAddress.getAllByName]).
 * Used where raw DNS forwarding is not available: answers A and AAAA, returns an empty
 * NOERROR for other types (callers fall back to A/AAAA), NXDOMAIN for unknown names.
 */
object MiniDns {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_HTTPS = 65

    data class Question(val name: String, val type: Int, val klass: Int, val end: Int)

    fun parseQuestion(q: ByteArray): Question? {
        if (q.size < 12 || IpPacket.u16(q, 4) < 1) return null
        val labels = ArrayList<String>()
        var i = 12
        while (true) {
            if (i >= q.size) return null
            val len = q[i].toInt() and 0xFF
            if (len == 0) { i++; break }
            if (len and 0xC0 != 0 || i + 1 + len > q.size) return null
            labels += String(q, i + 1, len, Charsets.US_ASCII)
            i += 1 + len
        }
        if (i + 4 > q.size) return null
        return Question(labels.joinToString("."), IpPacket.u16(q, i), IpPacket.u16(q, i + 2), i + 4)
    }

    fun answer(query: ByteArray, resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName): ByteArray? {
        val q = parseQuestion(query) ?: return null
        if (q.type != TYPE_A && q.type != TYPE_AAAA) return response(query, q, 0, emptyList())
        val addresses = try {
            resolve(q.name).filter { if (q.type == TYPE_A) it is Inet4Address else it is Inet6Address }
        } catch (e: UnknownHostException) {
            return response(query, q, 3, emptyList())
        }
        return response(query, q, 0, addresses)
    }

    fun noData(query: ByteArray): ByteArray? {
        val q = parseQuestion(query) ?: return null
        return response(query, q, 0, emptyList())
    }

    fun serverFailure(query: ByteArray): ByteArray? {
        val q = parseQuestion(query) ?: return null
        return response(query, q, 2, emptyList())
    }

    private fun response(query: ByteArray, q: Question, rcode: Int, addresses: List<InetAddress>): ByteArray {
        val out = ByteArrayOutputStream(q.end + addresses.size * 28)
        out.write(query, 0, 2) // id
        val rd = query[2].toInt() and 0x01
        out.write(0x80 or rd) // QR, opcode 0, RD copied
        out.write(0x80 or rcode) // RA
        out.write(byteArrayOf(0, 1)) // qdcount
        out.write(byteArrayOf((addresses.size ushr 8).toByte(), addresses.size.toByte())) // ancount
        out.write(byteArrayOf(0, 0, 0, 0)) // nscount, arcount
        out.write(query, 12, q.end - 12) // question
        for (a in addresses) {
            val raw = a.address
            out.write(byteArrayOf(0xC0.toByte(), 12)) // name: pointer to the question
            out.write(byteArrayOf(0, q.type.toByte(), 0, 1)) // type, class IN
            out.write(byteArrayOf(0, 0, 0, 60)) // TTL 60 s
            out.write(byteArrayOf(0, raw.size.toByte()))
            out.write(raw)
        }
        return out.toByteArray()
    }
}

/** Router platform for a plain JVM: system resolver for DNS, [InetAddress.isReachable] for ping. */
open class JvmRouterPlatform : RouterPlatform {
    override fun resolveDns(query: ByteArray): ByteArray? = MiniDns.answer(query)

    override fun ping(destination: InetAddress, payload: ByteArray, timeoutMs: Int): Boolean =
        destination.isReachable(timeoutMs)
}
