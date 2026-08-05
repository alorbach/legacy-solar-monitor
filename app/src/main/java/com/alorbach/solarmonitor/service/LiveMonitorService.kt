package com.alorbach.solarmonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.alorbach.solarmonitor.MainActivity
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.SolarMonitorApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LiveMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = intent?.getLongExtra(EXTRA_DEVICE_ID, -1L)?.takeIf { it > 0 } ?: return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, buildNotification("Connecting"))

        val container = (application as SolarMonitorApplication).container
        scope.launch {
            while (true) {
                container.liveMonitoringRepository.start(deviceId)
                val message = container.liveMonitoringRepository.state.value.message
                val notification = buildNotification(message)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
                delay(POLL_INTERVAL_MS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        (application as SolarMonitorApplication).container.liveMonitoringRepository.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Live monitoring: $message")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Live monitor", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        private const val CHANNEL_ID = "live_monitor"
        private const val NOTIFICATION_ID = 4001
        private const val POLL_INTERVAL_MS = 60_000L
    }
}
