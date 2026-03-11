package com.example.myapplication.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.myapplication.R
import com.example.myapplication.runtime.CodexRuntimeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BackendForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var runtimeController: CodexRuntimeController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runtimeController = CodexRuntimeController(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdownSession(stopBackend = false)
            }

            ACTION_SHUTDOWN -> {
                shutdownSession(stopBackend = true)
            }

            else -> {
                ensureNotificationChannel()
                startForeground(
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle(getString(R.string.backend_notification_title))
                        .setContentText(getString(R.string.backend_notification_text))
                        .setSmallIcon(android.R.drawable.stat_notify_sync)
                        .setOngoing(true)
                        .build(),
                )
                startMonitorLoop()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        shutdownSession(stopBackend = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        monitorStarted = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.backend_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.backend_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startMonitorLoop() {
        if (monitorStarted) {
            return
        }
        monitorStarted = true
        serviceScope.launch {
            while (isActive) {
                runCatching {
                    runtimeController.reapplyHardeningIfEnabled()
                    val status = runtimeController.getBackendStatus()
                    if (
                        status.optBoolean("termuxInstalled") &&
                        status.optBoolean("rootAvailable") &&
                        !status.optBoolean("backendListening")
                    ) {
                        runtimeController.ensureBackendRunning()
                    }
                }
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    private fun shutdownSession(stopBackend: Boolean) {
        monitorStarted = false
        serviceScope.coroutineContext.cancel()
        if (stopBackend) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { runtimeController.stopBackend() }
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "codex.mobile.backend.START"
        const val ACTION_STOP = "codex.mobile.backend.STOP"
        const val ACTION_SHUTDOWN = "codex.mobile.backend.SHUTDOWN"

        private const val CHANNEL_ID = "codex_mobile_backend"
        private const val NOTIFICATION_ID = 41042
        private const val MONITOR_INTERVAL_MS = 10_000L
        @Volatile
        private var monitorStarted: Boolean = false
    }
}
