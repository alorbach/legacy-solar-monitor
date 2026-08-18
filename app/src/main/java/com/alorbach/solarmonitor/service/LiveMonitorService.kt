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
import com.alorbach.solarmonitor.i18n.LocaleController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LiveMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollJobs = mutableMapOf<Long, Job>()
    private val jobsMutex = Mutex()
    /** Tracks the onStartCommand restart coordinator so stop can cancel it. */
    @Volatile private var coordinatorJob: Job? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleController.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            clearPersistedDeviceIds()
            stopMonitoring()
            return START_NOT_STICKY
        }

        val fromIntent = resolveDeviceIds(intent)
        val deviceIds = if (fromIntent.isNotEmpty()) fromIntent else loadPersistedDeviceIds()
        if (deviceIds.isEmpty()) return START_NOT_STICKY
        persistDeviceIds(deviceIds)

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.live_connecting)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )

        val container = (application as SolarMonitorApplication).container
        coordinatorJob?.cancel()
        coordinatorJob = scope.launch {
            jobsMutex.withLock {
                // Replace the whole set when a new start request arrives. Abort RFCOMM before
                // joining so cancelled poll loops are not stuck on a blocked Bluetooth read.
                val previous = pollJobs.values.toList()
                pollJobs.clear()
                previous.forEach { it.cancel() }
                container.liveMonitoringRepository.stopAll()
                previous.forEach { it.join() }
                // A poll that passed isActive before cancel can still finish start() after the
                // first stopAll; clear again so restart starts from a clean continuous set.
                container.liveMonitoringRepository.stopAll()
                ensureActive()
                deviceIds.forEach { deviceId ->
                    pollJobs[deviceId] = scope.launch {
                        while (isActive) {
                            val result = container.liveMonitoringRepository.start(deviceId, continuous = true)
                            val aggregate = container.liveMonitoringRepository.state.value.message
                            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            manager.notify(NOTIFICATION_ID, buildNotification(aggregate))
                            result.exceptionOrNull()
                            val intervalSeconds = runCatching {
                                container.settingsStore.settings.first().livePollIntervalSeconds
                            }.getOrDefault(POLL_INTERVAL_MS / 1000).coerceIn(15L, 3600L)
                            delay(intervalSeconds * 1000)
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopMonitoring()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun resolveDeviceIds(intent: Intent?): LongArray {
        val many = intent?.getLongArrayExtra(EXTRA_DEVICE_IDS)?.filter { it > 0 }?.toLongArray()
        if (many != null && many.isNotEmpty()) return many
        val one = intent?.getLongExtra(EXTRA_DEVICE_ID, -1L)?.takeIf { it > 0 }
        return if (one != null) longArrayOf(one) else longArrayOf()
    }

    private fun stopMonitoring() {
        // Cancel the restart coordinator first so it cannot relaunch polls after teardown.
        // Then cancel -> abort RFCOMM -> join. Joining before abort can block for a full
        // Bluetooth read timeout because live reads ignore coroutine cancellation until
        // the socket closes.
        runBlocking {
            val coordinator = coordinatorJob
            coordinatorJob = null
            coordinator?.cancel()
            coordinator?.join()
            jobsMutex.withLock {
                val jobs = pollJobs.values.toList()
                pollJobs.clear()
                jobs.forEach { it.cancel() }
                (application as? SolarMonitorApplication)?.container?.liveMonitoringRepository?.stopAll()
                jobs.forEach { it.join() }
                // Clear anything a late start(..., continuous=true) re-registered after abort.
                (application as? SolarMonitorApplication)?.container?.liveMonitoringRepository?.stopAll()
            }
        }
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
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.live_monitor_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun persistDeviceIds(ids: LongArray) {
        persistDeviceIds(this, ids)
    }

    private fun loadPersistedDeviceIds(): LongArray = persistedDeviceIds(this)

    private fun clearPersistedDeviceIds() {
        persistDeviceIds(this, longArrayOf())
    }

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_IDS = "device_ids"
        const val ACTION_STOP = "com.alorbach.solarmonitor.action.STOP_LIVE_MONITOR"
        private const val CHANNEL_ID = "live_monitor"
        private const val NOTIFICATION_ID = 4001
        private const val POLL_INTERVAL_MS = 60_000L
        private const val PREFS = "live_monitor"
        private const val KEY_DEVICE_IDS = "device_ids"

        fun persistedDeviceIds(context: Context): LongArray =
            context.applicationContext.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_DEVICE_IDS, "")
                .orEmpty()
                .split(',')
                .mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0 } }
                .toLongArray()

        fun persistDeviceIds(context: Context, ids: LongArray) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            if (ids.isEmpty()) {
                prefs.remove(KEY_DEVICE_IDS)
            } else {
                prefs.putString(KEY_DEVICE_IDS, ids.joinToString(","))
            }
            check(prefs.commit())
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, LiveMonitorService::class.java).setAction(ACTION_STOP)

        fun startIntent(context: Context, deviceIds: LongArray): Intent =
            Intent(context, LiveMonitorService::class.java)
                .putExtra(EXTRA_DEVICE_IDS, deviceIds)
                .also {
                    if (deviceIds.size == 1) it.putExtra(EXTRA_DEVICE_ID, deviceIds[0])
                }
    }
}
