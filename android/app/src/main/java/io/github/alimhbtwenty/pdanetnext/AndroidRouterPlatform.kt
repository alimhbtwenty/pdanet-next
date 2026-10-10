package io.github.alimhbtwenty.pdanetnext

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import io.github.alimhbtwenty.pdanetnext.core.router.MiniDns
import io.github.alimhbtwenty.pdanetnext.core.router.RouterPlatform
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Router hooks on Android. Both go through the phone's default network, which is the VPN
 * when one is active.
 */
class AndroidRouterPlatform : RouterPlatform {

    private val direct = Executor { it.run() }

    /** Android 10+: forward the raw query to the system resolver (all record types). Older: A/AAAA only. */
    override fun resolveDns(query: ByteArray): ByteArray? {
        if (Build.VERSION.SDK_INT < 29) return MiniDns.answer(query)
        val result = AtomicReference<ByteArray?>()
        val done = CountDownLatch(1)
        val cancel = CancellationSignal()
        DnsResolver.getInstance().rawQuery(
            null, query, DnsResolver.FLAG_EMPTY, direct, cancel,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    result.set(answer); done.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    done.countDown()
                }
            },
        )
        if (!done.await(8, TimeUnit.SECONDS)) cancel.cancel()
        return result.get() ?: MiniDns.answer(query)
    }

    /**
     * ICMP echo with an unprivileged "ping socket" (SOCK_DGRAM + IPPROTO_ICMP), which Android
     * allows for every app. The kernel sets the identifier and checksum.
     */
    override fun ping(destination: InetAddress, payload: ByteArray, timeoutMs: Int): Boolean {
        val v6 = destination is Inet6Address
        val fd = Os.socket(
            if (v6) OsConstants.AF_INET6 else OsConstants.AF_INET,
            OsConstants.SOCK_DGRAM,
            if (v6) OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP,
        )
        try {
            val seq = (System.nanoTime() and 0xFFFF).toInt()
            val request = ByteArray(8 + payload.size)
            request[0] = (if (v6) 128 else 8).toByte()
            request[6] = (seq ushr 8).toByte()
            request[7] = seq.toByte()
            payload.copyInto(request, 8)
            Os.sendto(fd, request, 0, request.size, 0, destination, 0)

            val deadline = System.currentTimeMillis() + timeoutMs
            val reply = ByteArray(65535)
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(left))
                val n = try {
                    Os.recvfrom(fd, reply, 0, reply.size, 0, null)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                    return false
                }
                val type = reply[0].toInt() and 0xFF
                val gotSeq = ((reply[6].toInt() and 0xFF) shl 8) or (reply[7].toInt() and 0xFF)
                if (n >= 8 && type == (if (v6) 129 else 0) && gotSeq == seq) return true
            }
        } catch (e: ErrnoException) {
            return false
        } finally {
            runCatching { Os.close(fd) }
        }
    }
}
