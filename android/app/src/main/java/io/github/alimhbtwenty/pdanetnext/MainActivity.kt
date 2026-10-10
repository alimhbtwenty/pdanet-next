package io.github.alimhbtwenty.pdanetnext

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var tvUplink: TextView
    private lateinit var tvVpn: TextView
    private lateinit var tvDown: TextView
    private lateinit var tvUp: TextView
    private lateinit var tvTcp: TextView
    private lateinit var tvUdp: TextView
    private lateinit var btnToggle: MaterialButton
    private lateinit var cardAdbWarning: View

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { RelayService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusDot = findViewById(R.id.statusDot)
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        tvUplink = findViewById(R.id.tvUplink)
        tvVpn = findViewById(R.id.tvVpn)
        tvDown = findViewById(R.id.tvDown)
        tvUp = findViewById(R.id.tvUp)
        tvTcp = findViewById(R.id.tvTcp)
        tvUdp = findViewById(R.id.tvUdp)
        btnToggle = findViewById(R.id.btnToggle)
        cardAdbWarning = findViewById(R.id.cardAdbWarning)

        btnToggle.setOnClickListener {
            if (RelayService.state.value.running) RelayService.stop(this) else startRelay()
        }
        findViewById<View>(R.id.btnDeveloperOptions).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                .onFailure { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { RelayService.state.collect(::render) }
                launch {
                    while (true) {
                        renderUplink(UplinkInfo.read(this@MainActivity))
                        delay(2000)
                    }
                }
            }
        }

        handleAutostart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutostart(intent)
    }

    /** The Windows app starts the relay with `am start -n <pkg>/.MainActivity --ez autostart true`. */
    private fun handleAutostart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true && !RelayService.state.value.running) {
            RelayService.start(this)
        }
    }

    private fun startRelay() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // The relay starts either way; the permission only makes its notification visible.
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            RelayService.start(this)
        }
    }

    private fun render(state: RelayService.State) {
        val (title, detail, color) = when {
            state.error != null -> Triple("Error", state.error, R.color.status_error)
            !state.running -> Triple("Off", "Tap Start, then press Connect in the PC app.", R.color.status_off)
            state.activeTcp > 0 -> Triple("PC connected", "Your PC is online through this phone.", R.color.status_on)
            else -> Triple("Ready", "Waiting for the PC app over USB.", R.color.status_wait)
        }
        tvStatus.text = title
        tvStatusDetail.text = detail
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(color))
        tvDown.text = formatBytes(state.bytesDown)
        tvUp.text = formatBytes(state.bytesUp)
        tvTcp.text = state.activeTcp.toString()
        tvUdp.text = state.activeUdp.toString()
        btnToggle.text = if (state.running) "Stop" else "Start"
        btnToggle.backgroundTintList = ColorStateList.valueOf(
            getColor(if (state.running) R.color.button_stop else R.color.button_start)
        )
    }

    private fun renderUplink(info: UplinkInfo) {
        tvUplink.text = when {
            !info.connected -> "No internet"
            info.transport == "mobile" -> "Mobile data"
            info.transport == "wifi" -> "Wi-Fi"
            info.transport == "ethernet" -> "Ethernet"
            else -> "Connected"
        }
        tvVpn.text = if (info.vpnActive) "On · the PC goes through it too" else "Off"
        tvVpn.setTextColor(getColor(if (info.vpnActive) R.color.status_on else R.color.text_secondary))
        cardAdbWarning.visibility = if (info.usbDebugging) View.GONE else View.VISIBLE
    }

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }
}
