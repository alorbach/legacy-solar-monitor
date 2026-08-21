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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

class LiveMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollJobs = mutableMapOf<Long, Job>()
    private val jobsMutex = Mutex()
    /** Tracks the onStartCommand restart coordinator so stop can cancel it. */
    @Volatile private var coordinatorJob: Job? = null
    private var stopJob: Job? = null
    /** Bumped on each start so an in-flight stop cannot teardown or stopSelf a newer session. */
    private val runGeneration = AtomicInteger(0)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleController.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LivePollScheduler.cancel(this)
            clearPersistedDeviceIds()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            val stopGen = runGeneration.get()
            val stopStartId = startId
            coordinatorJob?.cancel()
            stopJob?.cancel()
            (application as? SolarMonitorApplication)?.container?.liveMonitoringRepository?.stopAll()
            stopJob = scope.launch {
                teardownSessions(USER_STOP_JOIN_TIMEOUT_MS, stopGen)
                if (runGeneration.get() == stopGen) {
                    stopSelf(stopStartId)
                }
            }
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_PAUSE) {
            val pauseGen = intent.getIntExtra(EXTRA_RUN_GENERATION, -1)
            if (pauseGen >= 0 && pauseGen != runGeneration.get()) {
                return START_NOT_STICKY
            }
            val pauseStartId = startId
            coordinatorJob?.cancel()
            stopJob?.cancel()
            (application as? SolarMonitorApplication)?.container?.liveMonitoringRepository?.stopAll()
            stopJob = scope.launch {
                val ids = loadPersistedDeviceIds()
                LivePollScheduler.scheduleResume(this@LiveMonitorService, ids)
                teardownSessions(USER_STOP_JOIN_TIMEOUT_MS, pauseGen.takeIf { it >= 0 } ?: runGeneration.get())
                if (pauseGen < 0 || runGeneration.get() == pauseGen) {
                    ServiceCompat.stopForeground(
                        this@LiveMonitorService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE,
                    )
                    stopSelf(pauseStartId)
                }
            }
            return START_NOT_STICKY
        }

        val resumeOnly = intent?.action == ACTION_RESUME
        val incoming = if (resumeOnly) longArrayOf() else resolveDeviceIds(intent)
        val deviceIds = (loadPersistedDeviceIds().toList() + incoming.toList()).distinct().toLongArray()
        if (deviceIds.isEmpty()) {
            if (resumeOnly) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        val startGen = runGeneration.incrementAndGet()
        publishedRunGeneration.set(startGen)
        stopJob?.cancel()
        stopJob = null

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
        persistDeviceIds(deviceIds)

        val container = (application as SolarMonitorApplication).container
        coordinatorJob?.cancel()
        coordinatorJob = scope.launch {
            jobsMutex.withLock {
                if (runGeneration.get() != startGen) return@launch
                // Unioned device list when a new start request arrives. Abort RFCOMM before
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
                if (!LivePollScheduler.anyDeviceInWindow(this@LiveMonitorService, deviceIds)) {
                    LivePollScheduler.scheduleResume(this@LiveMonitorService, deviceIds)
                    ServiceCompat.stopForeground(
                        this@LiveMonitorService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE,
                    )
                    if (runGeneration.get() == startGen) {
                        stopSelf()
                    }
                    return@launch
                }
                LivePollScheduler.cancel(this@LiveMonitorService)
                val policy = LivePollScheduler.loadPolicy(this@LiveMonitorService, deviceIds)
                deviceIds.forEach { deviceId ->
                    pollJobs[deviceId] = launch {
                        while (isActive) {
                            val now = java.time.Instant.now()
                            if (!policy.isOpen(deviceId, now)) {
                                container.liveMonitoringRepository.stopDevice(deviceId)
                                val remaining = loadPersistedDeviceIds()
                                if (!policy.anyOpen(remaining)) {
                                    startService(
                                        pauseIntent(this@LiveMonitorService)
                                            .putExtra(EXTRA_RUN_GENERATION, startGen),
                                    )
                                    return@launch
                                }
                                delay(policy.millisUntilOpen(deviceId).coerceAtLeast(1_000L))
                                continue
                            }
                            val result = container.liveMonitoringRepository.start(deviceId, continuous = true)
                            val aggregate = container.liveMonitoringRepository.state.value.message
                            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            manager.notify(NOTIFICATION_ID, buildNotification(aggregate))
                            result.exceptionOrNull()
                            val intervalSeconds = runCatching {
                                container.settingsStore.settings.first().livePollIntervalSeconds
                            }.getOrDefault(POLL_INTERVAL_MS / 1000).coerceIn(15L, 3600L)
                            val intervalMs = intervalSeconds * 1000L
                            val untilClose = policy.millisUntilClose(deviceId)
                            val sleepMs = if (untilClose == Long.MAX_VALUE) {
                                intervalMs
                            } else {
                                minOf(intervalMs, untilClose)
                            }
                            delay(sleepMs.coerceAtLeast(0L))
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        coordinatorJob?.cancel()
        stopJob?.cancel()
        pollJobs.values.forEach { it.cancel() }
        pollJobs.clear()
        (application as? SolarMonitorApplication)?.container?.liveMonitoringRepository?.stopAll()
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

    /**
     * Abort RFCOMM and join poll jobs. Caller must not block the main thread;
     * [onStartCommand] launches this on [scope] (IO). [onDestroy] cancels jobs and aborts
     * sockets without joining.
     */
    private suspend fun teardownSessions(joinTimeoutMs: Long, expectedGeneration: Int?) {
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(joinTimeoutMs) {
                if (expectedGeneration != null && runGeneration.get() != expectedGeneration) return@withTimeoutOrNull
                val coordinator = coordinatorJob
                coordinator?.cancel()
                if (coordinatorJob === coordinator) {
                    coordinatorJob = null
                }
                coordinator?.join()
                jobsMutex.withLock {
                    if (expectedGeneration != null && runGeneration.get() != expectedGeneration) return@withLock
                    val jobs = pollJobs.values.toList()
                    pollJobs.clear()
                    jobs.forEach { it.cancel() }
                    (application as? SolarMonitorApplication)?.container?.liveMonitoringRepository?.stopAll()
                    jobs.forEach { it.join() }
                    (application as? SolarMonitorApplication)?.container?.liveMonitoringRepository?.stopAll()
                }
            } ?: run {
                if (expectedGeneration == null || runGeneration.get() == expectedGeneration) {
                    jobsMutex.withLock {
                        val jobs = pollJobs.values.toList()
                        pollJobs.clear()
                        jobs.forEach { it.cancel() }
                    }
                    (application as? SolarMonitorApplication)?.container?.liveMonitoringRepository?.stopAll()
                }
            }
        }
    }

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.live_monitoring_status, message))
            .setSmallIcon(R.drawable.ic_stat_notify)
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
        const val ACTION_PAUSE = "com.alorbach.solarmonitor.action.PAUSE_LIVE_MONITOR"
        const val ACTION_RESUME = "com.alorbach.solarmonitor.action.RESUME_LIVE_MONITOR"
        const val EXTRA_RUN_GENERATION = "run_generation"
        private const val CHANNEL_ID = "live_monitor"
        private const val NOTIFICATION_ID = 4001
        private const val POLL_INTERVAL_MS = 60_000L
        private const val USER_STOP_JOIN_TIMEOUT_MS = 30_000L
        private const val PREFS = "live_monitor"
        private const val KEY_DEVICE_IDS = "device_ids"
        private val publishedRunGeneration = AtomicInteger(0)

        fun currentRunGeneration(): Int = publishedRunGeneration.get()

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

        fun pauseIntent(context: Context): Intent =
            Intent(context, LiveMonitorService::class.java).setAction(ACTION_PAUSE)

        /** Resume from alarm/boot/settings using only currently persisted IDs (honors Stop). */
        fun resumeIntent(context: Context): Intent =
            Intent(context, LiveMonitorService::class.java).setAction(ACTION_RESUME)

        fun startIntent(context: Context, deviceIds: LongArray): Intent =
            Intent(context, LiveMonitorService::class.java)
                .putExtra(EXTRA_DEVICE_IDS, deviceIds)
                .also {
                    if (deviceIds.size == 1) it.putExtra(EXTRA_DEVICE_ID, deviceIds[0])
                }
    }
}
