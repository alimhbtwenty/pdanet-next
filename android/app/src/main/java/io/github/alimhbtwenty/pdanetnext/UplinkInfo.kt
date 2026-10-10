package io.github.alimhbtwenty.pdanetnext

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings

/** What the relay's outgoing sockets will use right now. */
data class UplinkInfo(
    val connected: Boolean,
    val transport: String,
    val vpnActive: Boolean,
    val usbDebugging: Boolean,
) {
    companion object {
        fun read(context: Context): UplinkInfo {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            // With a VPN up, the default network *is* the VPN; its underlying transport is also flagged.
            val transport = when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
            val adb = runCatching {
                Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
            }.getOrDefault(true)
            return UplinkInfo(
                connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                transport = transport,
                vpnActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
                usbDebugging = adb,
            )
        }
    }

    fun toStatus(): Map<String, Any?> = mapOf(
        "uplinkConnected" to connected,
        "uplink" to transport,
        "vpnActive" to vpnActive,
    )
}
