package io.github.alimhbtwenty.pdanext.core.router

import io.github.alimhbtwenty.pdanext.core.LogLevel
import io.github.alimhbtwenty.pdanext.core.LogSource
import java.io.IOException
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * One TCP connection from the PC, terminated on the phone and re-created as a [Socket].
 *
 * The PC's TCP stack talks to this class as if it were the remote host. The USB link is
 * reliable and ordered, so this is a deliberately small TCP: no window scaling, no SACK,
 * a fixed receive window for back-pressure, and simple timeout/duplicate-ACK retransmission
 * for the rare packet the PC drops.
 *
 * Sequence numbers are kept as Int: arithmetic wraps exactly like TCP's 32-bit space, and
 * `(a - b) > 0` means "a is after b".
 */
internal class TcpSession(private val router: Router, val key: FlowKey, syn: IpPacket) {

    private enum class State { CONNECTING, SYN_RCVD, ESTABLISHED, CLOSED }

    private class Segment(val seq: Int, val data: ByteArray, val fin: Boolean, var sentAt: Long) {
        val end get() = seq + data.size + (if (fin) 1 else 0)
    }

    private val lock = Object()
    @Volatile private var state = State.CONNECTING
    @Volatile private var socket: Socket? = null

    private val ourMss = if (key.remote is Inet6Address) router.tcpMss6 else router.tcpMss4
    private val segmentSize = minOf(syn.tcpMss() ?: 536, ourMss).coerceAtLeast(64)

    // PC -> remote
    private var rcvNxt = (syn.tcpSeq + 1).toInt()
    private val toRemote = LinkedBlockingQueue<ByteArray>()
    private val queuedBytes = AtomicInteger()
    private var pcFin = false
    private var lastAdvertisedWindow = RECV_WINDOW

    // remote -> PC
    private val iss = Random.nextInt()
    private var sndUna = iss
    private var sndNxt = iss + 1
    private var pcWindow = syn.tcpWindow
    private val unacked = ArrayDeque<Segment>()
    private var ourFinSent = false
    private var dupAcks = 0
    private var synAckSentAt = 0L
    private var retries = 0

    fun start() {
        router.executor.execute(::connect)
    }

    private fun connect() {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(key.remote, key.remotePort), CONNECT_TIMEOUT_MS)
            s.tcpNoDelay = true
            s.keepAlive = true
        } catch (e: Exception) {
            runCatching { s.close() }
            val fails = router.stats.failedConnections.incrementAndGet()
            if (fails <= 5 || fails % 10L == 0L) {
                router.log(LogLevel.WARN, LogSource.ROUTER, "TCP connect to ${key.remote.hostAddress}:${key.remotePort} failed: ${e.message} (total failed flows: $fails)")
            }
            synchronized(lock) {
                if (state == State.CLOSED) return
                // Refused / unreachable: answer the SYN with RST so the PC app fails fast.
                send(TCP_RST or TCP_ACK, 0, rcvNxt, 0)
                finish()
            }
            return
        }
        synchronized(lock) {
            if (state == State.CLOSED) {
                runCatching { s.close() }
                return
            }
            socket = s
            state = State.SYN_RCVD
            sendSynAck()
        }
        router.executor.execute(::writerLoop)
        router.executor.execute(::readerLoop)
    }

    /** Called on the router's input thread; never blocks. */
    fun onPacket(p: IpPacket) {
        synchronized(lock) { onPacketLocked(p) }
    }

    private fun onPacketLocked(p: IpPacket) {
        val flags = p.tcpFlags
        if (state == State.CLOSED) return
        if (flags and TCP_RST != 0) {
            abort(sendReset = false)
            return
        }
        if (flags and TCP_SYN != 0) {
            // Retransmitted SYN: our SYN-ACK was lost or is still on its way.
            if (state == State.SYN_RCVD) sendSynAck()
            return
        }
        if (state == State.CONNECTING) return

        if (flags and TCP_ACK != 0) handleAck(p)

        var needAck = false
        val seq = p.tcpSeq.toInt()
        val len = p.tcpDataLength
        if (len > 0 && state == State.ESTABLISHED) {
            needAck = true
            if (seq == rcvNxt && !pcFin && queuedBytes.get() + len <= RECV_WINDOW) {
                toRemote.add(p.copyOf(p.tcpDataOffset, len))
                queuedBytes.addAndGet(len)
                rcvNxt += len
            }
            // Anything else (duplicate, out of order, over the window) is dropped; the
            // ACK below tells the PC where we are.
        }
        if (flags and TCP_FIN != 0 && state == State.ESTABLISHED) {
            needAck = true
            if (!pcFin && seq + len == rcvNxt) {
                pcFin = true
                rcvNxt += 1
                toRemote.add(EOF)
            }
        }
        if (needAck) sendAck()
        maybeFinish()
    }

    private fun handleAck(p: IpPacket) {
        val ack = p.tcpAck.toInt()
        val windowChanged = p.tcpWindow != pcWindow
        pcWindow = p.tcpWindow
        if (state == State.SYN_RCVD) {
            if (ack == sndNxt) {
                state = State.ESTABLISHED
                sndUna = ack
                retries = 0
            }
            lock.notifyAll()
            return
        }
        if (ack - sndUna > 0 && ack - sndNxt <= 0) {
            sndUna = ack
            while (unacked.isNotEmpty() && unacked.first().end - ack <= 0) unacked.removeFirst()
            dupAcks = 0
            retries = 0
        } else if (ack == sndUna && unacked.isNotEmpty() && p.tcpDataLength == 0 && !windowChanged &&
            p.tcpFlags and (TCP_SYN or TCP_FIN) == 0
        ) {
            if (++dupAcks == 3) retransmit(unacked.first(), System.currentTimeMillis())
        }
        lock.notifyAll()
    }

    /** remote -> PC */
    private fun readerLoop() {
        val input = try { socket!!.getInputStream() } catch (e: IOException) { abort(true); return }
        val buf = ByteArray(segmentSize)
        try {
            while (true) {
                val space = awaitSendSpace()
                if (space < 0) return
                val n = input.read(buf, 0, space)
                synchronized(lock) {
                    if (state == State.CLOSED) return
                    val now = System.currentTimeMillis()
                    if (n < 0) {
                        unacked.addLast(Segment(sndNxt, EMPTY, true, now))
                        send(TCP_FIN or TCP_ACK, sndNxt, rcvNxt, window())
                        sndNxt += 1
                        ourFinSent = true
                        maybeFinish()
                        return
                    }
                    val data = buf.copyOf(n)
                    unacked.addLast(Segment(sndNxt, data, false, now))
                    send(TCP_ACK or TCP_PSH, sndNxt, rcvNxt, window(), data)
                    sndNxt += n
                    router.stats.bytesDown.addAndGet(n.toLong())
                }
            }
        } catch (e: IOException) {
            abort(sendReset = true)
        } catch (e: InterruptedException) {
            abort(sendReset = false)
        }
    }

    /** Blocks until the PC's window has room; -1 once the session is closed. */
    private fun awaitSendSpace(): Int {
        synchronized(lock) {
            while (true) {
                if (state == State.CLOSED) return -1
                if (state == State.ESTABLISHED) {
                    val space = minOf(segmentSize, pcWindow - (sndNxt - sndUna))
                    if (space > 0) return space
                }
                lock.wait(500)
            }
        }
    }

    /** PC -> remote */
    private fun writerLoop() {
        try {
            val s = socket ?: return
            val out = s.getOutputStream()
            while (true) {
                val chunk = toRemote.take()
                if (chunk === EOF) {
                    if (state != State.CLOSED) runCatching { s.shutdownOutput() }
                    return
                }
                out.write(chunk)
                router.stats.bytesUp.addAndGet(chunk.size.toLong())
                val queued = queuedBytes.addAndGet(-chunk.size)
                synchronized(lock) {
                    // Window update once a meaningful amount of buffer has freed up.
                    if (state == State.ESTABLISHED && (RECV_WINDOW - queued) - lastAdvertisedWindow >= RECV_WINDOW / 2) sendAck()
                }
            }
        } catch (e: IOException) {
            abort(sendReset = true)
        } catch (e: InterruptedException) {
            abort(sendReset = false)
        }
    }

    fun onTimer(now: Long) {
        synchronized(lock) { onTimerLocked(now) }
    }

    private fun onTimerLocked(now: Long) {
        when (state) {
            State.SYN_RCVD -> if (now - synAckSentAt > RTO_MS) {
                if (++retries > MAX_RETRIES) abort(true) else sendSynAck()
            }
            State.ESTABLISHED -> unacked.firstOrNull()?.let { first ->
                if (now - first.sentAt > RTO_MS) {
                    if (++retries > MAX_RETRIES) abort(true) else retransmit(first, now)
                }
            }
            else -> {}
        }
    }

    fun abort(sendReset: Boolean) {
        synchronized(lock) {
            if (state == State.CLOSED) return
            if (sendReset && state != State.CONNECTING) send(TCP_RST or TCP_ACK, sndNxt, rcvNxt, 0)
            finish()
        }
    }

    private fun maybeFinish() {
        if (pcFin && ourFinSent && sndUna == sndNxt) finish()
    }

    private fun finish() {
        state = State.CLOSED
        runCatching { socket?.close() }
        toRemote.clear()
        toRemote.add(EOF)
        unacked.clear()
        lock.notifyAll()
        router.tcpClosed(key)
    }

    private fun retransmit(seg: Segment, now: Long) {
        val flags = if (seg.fin) TCP_FIN or TCP_ACK else TCP_ACK or TCP_PSH
        send(flags, seg.seq, rcvNxt, window(), seg.data)
        seg.sentAt = now
    }

    private fun sendSynAck() {
        synAckSentAt = System.currentTimeMillis()
        router.send(
            PacketBuilder.tcp(
                key.remote, key.remotePort, key.pc, key.pcPort,
                iss.toUInt().toLong(), rcvNxt.toUInt().toLong(), TCP_SYN or TCP_ACK, window(), mss = ourMss,
            )
        )
    }

    private fun sendAck() = send(TCP_ACK, sndNxt, rcvNxt, window())

    private fun window(): Int {
        val w = (RECV_WINDOW - queuedBytes.get()).coerceAtLeast(0)
        lastAdvertisedWindow = w
        return w
    }

    private fun send(flags: Int, seq: Int, ack: Int, window: Int, data: ByteArray = EMPTY) {
        router.send(
            PacketBuilder.tcp(
                key.remote, key.remotePort, key.pc, key.pcPort,
                seq.toUInt().toLong(), ack.toUInt().toLong(), flags, window,
                data, 0, data.size,
            )
        )
    }

    companion object {
        /** Receive window advertised to the PC (no window scaling). */
        const val RECV_WINDOW = 65535
        const val RTO_MS = 1000L
        const val MAX_RETRIES = 12
        const val CONNECT_TIMEOUT_MS = 10_000
        private val EMPTY = ByteArray(0)
        private val EOF = ByteArray(0)
    }
}
