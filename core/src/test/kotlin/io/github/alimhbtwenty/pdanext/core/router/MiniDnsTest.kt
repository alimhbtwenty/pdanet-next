package io.github.alimhbtwenty.pdanext.core.router

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiniDnsTest {

    private fun buildQuery(name: String, type: Int): ByteArray {
        val q = ByteArrayOutputStream()
        q.write(byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        name.split('.').forEach {
            q.write(it.length)
            q.write(it.toByteArray())
        }
        q.write(0)
        q.write(byteArrayOf(0, type.toByte(), 0, 1))
        return q.toByteArray()
    }

    @Test
    fun `parseAnswers extracts A record and TTL from response`() {
        val query = buildQuery("example.com", MiniDns.TYPE_A)
        val answer = MiniDns.answer(query) { arrayOf(InetAddress.getByName("93.184.216.34")) }!!
        val records = MiniDns.parseAnswers(answer)
        assertEquals(1, records.size)
        assertEquals(InetAddress.getByName("93.184.216.34"), records[0].address)
        assertEquals(60L, records[0].ttlSeconds)
    }

    @Test
    fun `parseAnswers extracts AAAA record and TTL`() {
        val query = buildQuery("example.com", MiniDns.TYPE_AAAA)
        val answer = MiniDns.answer(query) { arrayOf(InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")) }!!
        val records = MiniDns.parseAnswers(answer)
        assertEquals(1, records.size)
        assertEquals(InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946"), records[0].address)
        assertEquals(60L, records[0].ttlSeconds)
    }

    @Test
    fun `parseAnswers extracts multiple A records with different addresses`() {
        val query = buildQuery("multiple.test", MiniDns.TYPE_A)
        val addrs = arrayOf(InetAddress.getByName("1.2.3.4"), InetAddress.getByName("5.6.7.8"))
        val answer = MiniDns.answer(query) { addrs }!!
        val records = MiniDns.parseAnswers(answer)
        assertEquals(2, records.size)
        assertEquals(InetAddress.getByName("1.2.3.4"), records[0].address)
        assertEquals(InetAddress.getByName("5.6.7.8"), records[1].address)
    }

    @Test
    fun `parseAnswers skips CNAME records and extracts following A record`() {
        val out = ByteArrayOutputStream()
        // Header: ID=0x5678, QR=1, RD=1, RA=1, QDCOUNT=1, ANCOUNT=2
        out.write(byteArrayOf(0x56, 0x78.toByte(), 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 2, 0, 0, 0, 0))
        // Question: alias.test, type A, class IN
        "alias.test".split('.').forEach { out.write(it.length); out.write(it.toByteArray()) }
        out.write(0)
        out.write(byteArrayOf(0, 1, 0, 1))

        // Answer 1: CNAME canonical.test (TTL=300, type 5)
        out.write(byteArrayOf(0xC0.toByte(), 12)) // pointer to question name
        out.write(byteArrayOf(0, 5, 0, 1)) // type CNAME, class IN
        out.write(byteArrayOf(0, 0, 1, 0x2C)) // TTL 300
        val targetName = ByteArrayOutputStream()
        "canonical.test".split('.').forEach { targetName.write(it.length); targetName.write(it.toByteArray()) }
        targetName.write(0)
        val cnameBytes = targetName.toByteArray()
        out.write(byteArrayOf(0, cnameBytes.size.toByte()))
        out.write(cnameBytes)

        // Answer 2: A 198.51.100.1 (TTL=120, type 1)
        out.write(byteArrayOf(0xC0.toByte(), 12)) // pointer
        out.write(byteArrayOf(0, 1, 0, 1)) // type A, class IN
        out.write(byteArrayOf(0, 0, 0, 120)) // TTL 120
        out.write(byteArrayOf(0, 4)) // rdlength 4
        out.write(byteArrayOf(198.toByte(), 51, 100, 1))

        val records = MiniDns.parseAnswers(out.toByteArray())
        assertEquals(1, records.size)
        assertEquals(InetAddress.getByName("198.51.100.1"), records[0].address)
        assertEquals(120L, records[0].ttlSeconds)
    }

    @Test
    fun `parseAnswers returns empty on empty answers or truncated data`() {
        val query = buildQuery("nodata.test", MiniDns.TYPE_A)
        val noData = MiniDns.noData(query)!!
        assertTrue(MiniDns.parseAnswers(noData).isEmpty())

        val fail = MiniDns.serverFailure(query)!!
        assertTrue(MiniDns.parseAnswers(fail).isEmpty())

        assertTrue(MiniDns.parseAnswers(ByteArray(10)).isEmpty())
        assertTrue(MiniDns.parseAnswers(ByteArray(0)).isEmpty())
    }
}
