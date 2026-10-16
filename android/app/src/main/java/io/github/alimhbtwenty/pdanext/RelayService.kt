package io.github.alimhbtwenty.pdanext

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.alimhbtwenty.pdanext.core.LogLevel
import io.github.alimhbtwenty.pdanext.core.LogSource
import io.github.alimhbtwenty.pdanext.core.RelayServer
import io.github.alimhbtwenty.pdanext.core.RotatingFileLogger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the relay. The PC reaches it through `adb forward`,
 * so it only listens on the phone's loopback interface.
 */
class RelayService : Service() {

    data class State(
        val running: Boolean = false,
        val error: String? = null,
        val bytesUp: Long = 0,
        val bytesDown: Long = 0,
        val activeTcp: Long = 0,
        val activeUdp: Long = 0,
        /** True while the PC is attached in "phone as router" mode. */
        val routerMode: Boolean = false,
    )

    private var relay: RelayServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification("Starting…"),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) {
            // Android 12+ refuses foreground starts from the background (e.g. a sticky restart).
            Log.w(TAG, "cannot start in foreground now", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (relay?.isRunning == true) return START_STICKY

        val rotatingLogger = RotatingFileLogger(File(filesDir, "logs"), maxBytes = 1024 * 1024, maxFiles = 2)
        val server = RelayServer(
            port = RelayServer.DEFAULT_PORT,
            log = { Log.d(TAG, it) },
            statusExtras = { UplinkInfo.read(this).toStatus() + ("device" to "${Build.MANUFACTURER} ${Build.MODEL}") },
            routerPlatform = AndroidRouterPlatform(),
        )
        server.logBuffer.addListener { _, line -> rotatingLogger.append(line) }
        server.logBuffer.log(LogLevel.INFO, LogSource.SERVICE, "Relay service starting")
        relay = server
        current = server
        scope.launch(Dispatchers.IO) {
            try {
                server.start()
            } catch (e: Exception) {
                Log.e(TAG, "relay failed to start", e)
                _state.value = State(error = "Port ${RelayServer.DEFAULT_PORT} is busy: ${e.message}")
                stopSelf()
                return@launch
            }
            acquireLocks()
            publishStats(server)
        }
        return START_STICKY
    }

    private suspend fun publishStats(server: RelayServer) {
        val nm = getSystemService(NotificationManager::class.java)
        var lastText = ""
        _state.value = State(running = true)
        while (scope.isActive && server.isRunning) {
            val s = server.stats
            val state = State(
                running = true,
                bytesUp = s.bytesUp.get(),
                bytesDown = s.bytesDown.get(),
                activeTcp = s.activeTcp.get(),
                activeUdp = s.activeUdp.get(),
                routerMode = server.activeRouter != null,
            )
            _state.value = state
            val text = if (state.routerMode || state.activeTcp > 0) {
                "PC connected · ↓ ${formatBytes(state.bytesDown)} · ↑ ${formatBytes(state.bytesUp)}"
            } else {
                "Waiting for the PC on USB"
            }
            if (text != lastText) {
                nm.notify(NOTIFICATION_ID, notification(text))
                lastText = text
            }
            delay(1000)
        }
    }

    private fun acquireLocks() {
        // Keep the CPU and (if it is the uplink) WiFi awake while the screen is off.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PdaNext:relay")
            .apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(mode, "PdaNext:relay")
            ?.apply { setReferenceCounted(false); acquire() }
    }

    override fun onDestroy() {
        relay?.logBuffer?.log(LogLevel.INFO, LogSource.SERVICE, "Relay service stopping")
        scope.cancel()
        relay?.stop()
        relay = null
        current = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        if (_state.value.error == null) _state.value = State()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "USB tether", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shows while the PC can use this phone's connection" }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RelayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tether)
            .setContentTitle("PdaNext is sharing internet")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "PdaNext"
        private const val CHANNEL_ID = "relay"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "io.github.alimhbtwenty.pdanext.STOP"

        @Volatile private var current: RelayServer? = null

        /** Router mode: ICMP ping from the phone to the PC; milliseconds, or null. Blocking. */
        fun pingPc(): Long? = current?.activeRouter?.pingClient()

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(java.util.Locale.US, "%d KB", bytes / 1024)
}
