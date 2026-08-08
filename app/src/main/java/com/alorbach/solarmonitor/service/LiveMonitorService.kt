package com.alorbach.solarmonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.alorbach.solarmonitor.MainActivity
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.SolarMonitorApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LiveMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        val deviceId = intent?.getLongExtra(EXTRA_DEVICE_ID, -1L)?.takeIf { it > 0 }
            ?: return START_NOT_STICKY

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Connecting"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )

        pollJob?.cancel()
        val container = (application as SolarMonitorApplication).container
        pollJob = scope.launch {
            while (isActive) {
                val result = container.liveMonitoringRepository.start(deviceId, continuous = true)
                val message = result.fold(
                    onSuccess = { it.status ?: "Connected" },
                    onFailure = { it.message ?: "Connection failed" },
                )
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification(message))
                delay(POLL_INTERVAL_MS)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopMonitoring()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopMonitoring() {
        pollJob?.cancel()
        pollJob = null
        (application as? SolarMonitorApplication)?.container?.liveMonitoringRepository?.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.live_monitoring_status, message))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.stop_live_monitoring),
                android.app.PendingIntent.getService(
                    this,
                    1,
                    Intent(this, LiveMonitorService::class.java).setAction(ACTION_STOP),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
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
        const val ACTION_STOP = "com.alorbach.solarmonitor.action.STOP_LIVE_MONITOR"
        private const val CHANNEL_ID = "live_monitor"
        private const val NOTIFICATION_ID = 4001
        private const val POLL_INTERVAL_MS = 60_000L

        fun stopIntent(context: Context): Intent =
            Intent(context, LiveMonitorService::class.java).setAction(ACTION_STOP)
    }
}
