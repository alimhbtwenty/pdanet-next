package io.github.alimhbtwenty.pdanext.core.router

import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/** Where the router sends a flow: out of the phone's own network, or through the Xray tunnel. */
enum class Route { DIRECT, TUNNEL }

/**
 * Decides which PC flows skip the tunnel and leave with the phone's domestic IP.
 *
 * A flow is direct when its destination is in [ranges] (the Iranian IPv4 list), or is an address
 * that a direct DNS answer returned for a listed name ([learn]). Names are direct when they end in
 * `.ir` or match [domains] (suffix match: `example.com` covers `www.example.com`).
 * Everything else goes through the tunnel; a tunnel failure must never fall back to direct.
 */
class BypassPolicy(domains: Collection<String> = emptyList(), val ranges: Ipv4Ranges = Ipv4Ranges.EMPTY) {
    private val ownerDomains = java.util.concurrent.CopyOnWriteArraySet<String>().apply {
        addAll(domains.mapNotNull(::hostOf))
    }
    val domains: Set<String> get() = ownerDomains
    private val learned = ConcurrentHashMap<InetAddress, Long>()

    fun setDomains(newDomains: Collection<String>) {
        ownerDomains.clear()
        ownerDomains.addAll(newDomains.mapNotNull(::hostOf))
    }

    fun addDomains(newDomains: Collection<String>) {
        ownerDomains.addAll(newDomains.mapNotNull(::hostOf))
    }

    fun isDomesticName(name: String): Boolean {
        var n = name.lowercase().trimEnd('.')
        if (n == "ir" || n.endsWith(".ir")) return true
        while (true) {
            if (n in ownerDomains) return true
            val dot = n.indexOf('.')
            if (dot < 0) return false
            n = n.substring(dot + 1)
        }
    }

    /** Remembers [addresses] from a direct answer for a domestic name, for [ttlSeconds] (at least a minute). */
    fun learn(addresses: Collection<InetAddress>, ttlSeconds: Long, now: Long = System.currentTimeMillis()) {
        val until = now + maxOf(ttlSeconds, 60) * 1000
        for (a in addresses) learned.merge(a, until, ::maxOf)
    }

    fun route(destination: InetAddress, now: Long = System.currentTimeMillis()): Route {
        if (destination is Inet4Address && ranges.contains(destination)) return Route.DIRECT
        val until = learned[destination] ?: return Route.TUNNEL
        if (until >= now) return Route.DIRECT
        learned.remove(destination, until)
        return Route.TUNNEL
    }

    companion object {
        /**
         * Reduces a list entry to a host name: drops the scheme, credentials, path, port, a leading
         * `www.` and the trailing dot. `https://www.Example.com:8443/a?b` -> `example.com`.
         */
        fun hostOf(entry: String): String? {
            var s = entry.trim().lowercase()
            if (s.isEmpty() || s.startsWith("#")) return null
            s = s.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            s = s.substringAfter('@').substringBefore(':').trimEnd('.')
            if (s.startsWith("www.")) s = s.removePrefix("www.")
            if (s.startsWith("*.")) s = s.removePrefix("*.")
            return s.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }
        }
    }
}

/** A set of IPv4 CIDR ranges, merged and searched by binary search. */
class Ipv4Ranges private constructor(private val starts: LongArray, private val ends: LongArray) {
    val size get() = starts.size

    fun contains(address: Inet4Address): Boolean {
        val v = toLong(address.address)
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                v < starts[mid] -> hi = mid - 1
                v > ends[mid] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    companion object {
        val EMPTY = Ipv4Ranges(LongArray(0), LongArray(0))

        /**
         * One `a.b.c.d/n` per line; blank lines, `#` comments and IPv6 entries are skipped.
         * If [lenient] is true, invalid lines that cannot be parsed as a CIDR are also skipped.
         */
        fun parse(text: String, lenient: Boolean = false): Ipv4Ranges {
            val ranges = text.lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() && ':' !in it }
                .mapNotNull { line ->
                    if (lenient) {
                        try {
                            parseCidr(line)
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        parseCidr(line)
                    }
                }
                .sortedBy { it.first }
                .toList()
            val starts = ArrayList<Long>()
            val ends = ArrayList<Long>()
            for ((s, e) in ranges) {
                if (ends.isNotEmpty() && s <= ends.last() + 1) {
                    ends[ends.size - 1] = maxOf(ends.last(), e)
                } else {
                    starts += s
                    ends += e
                }
            }
            return Ipv4Ranges(starts.toLongArray(), ends.toLongArray())
        }

        fun parseLenient(text: String): Ipv4Ranges = parse(text, lenient = true)

        private fun parseCidr(cidr: String): Pair<Long, Long> {
            val prefix = cidr.substringAfter('/', "32").toIntOrNull()
            require(prefix != null && prefix in 0..32) { "bad prefix in $cidr" }
            val parts = cidr.substringBefore('/').split('.')
            require(parts.size == 4) { "bad address in $cidr" }
            val bytes = ByteArray(4) {
                val b = parts[it].toIntOrNull()
                require(b != null && b in 0..255) { "bad address in $cidr" }
                b.toByte()
            }
            val size = 1L shl (32 - prefix)
            val start = toLong(bytes) and (size - 1).inv() and 0xFFFFFFFFL
            return start to start + size - 1
        }

        private fun toLong(b: ByteArray): Long =
            ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
                ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
    }
}
