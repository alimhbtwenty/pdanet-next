package io.github.alimhbtwenty.pdanetnext

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var proxyService: ProxyService? = null
    private var isBound = false
    private var isEngineRunning = false

    private lateinit var tvStatus: TextView
    private lateinit var tvTransfer: TextView
    private lateinit var tvClients: TextView
    private lateinit var btnToggle: Button

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ProxyService.LocalBinder
            proxyService = binder.getService()
            isBound = true
            isEngineRunning = true
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            proxyService = null
            isBound = false
            isEngineRunning = false
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvTransfer = findViewById(R.id.tvTransfer)
        tvClients = findViewById(R.id.tvClients)
        btnToggle = findViewById(R.id.btnToggle)

        btnToggle.setOnClickListener {
            if (isEngineRunning) {
                stopProxy()
            } else {
                startProxy()
            }
        }

        // Live stats updater
        lifecycleScope.launch {
            while (true) {
                delay(1000)
                if (isEngineRunning && proxyService != null) {
                    val bytes = proxyService!!.proxyServer.bytesTransferred.get()
                    val clients = proxyService!!.proxyServer.activeConnections.get()
                    tvTransfer.text = formatBytes(bytes)
                    tvClients.text = clients.toString()
                }
            }
        }
    }

    private fun startProxy() {
        val intent = Intent(this, ProxyService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        isEngineRunning = true
        updateUI()
    }

    private fun stopProxy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        val intent = Intent(this, ProxyService::class.java)
        stopService(intent)
        isEngineRunning = false
        updateUI()
    }

    private fun updateUI() {
        if (isEngineRunning) {
            tvStatus.text = "ONLINE (Ready for Windows 11 USB connect)"
            tvStatus.setTextColor(getColor(android.R.color.holo_green_light))
            btnToggle.text = "STOP USB TETHER"
        } else {
            tvStatus.text = "OFFLINE"
            tvStatus.setTextColor(getColor(android.R.color.darker_gray))
            btnToggle.text = "START USB TETHER"
            tvTransfer.text = "0 MB"
            tvClients.text = "0"
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.2f MB", mb)
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}
