package io.github.alimhbtwenty.pdanext.core

import java.util.concurrent.atomic.AtomicLong

/** Live counters, read by the phone UI and by the PC through the status endpoint. */
class RelayStats {
    /** Bytes sent from the PC to the internet. */
    val bytesUp = AtomicLong()
    /** Bytes received from the internet for the PC. */
    val bytesDown = AtomicLong()
    val activeTcp = AtomicLong()
    val activeUdp = AtomicLong()
    val totalConnections = AtomicLong()
    val failedConnections = AtomicLong()

    fun reset() {
        listOf(bytesUp, bytesDown, activeTcp, activeUdp, totalConnections, failedConnections).forEach { it.set(0) }
    }
}
