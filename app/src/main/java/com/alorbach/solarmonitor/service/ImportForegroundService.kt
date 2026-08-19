package com.alorbach.solarmonitor.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import com.alorbach.solarmonitor.SolarMonitorApplication
import com.alorbach.solarmonitor.i18n.LocaleController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

class ImportForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var importJob: Job? = null
    private val session = AtomicInteger(0)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleController.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        ImportNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as SolarMonitorApplication).container
        if (intent?.action == ACTION_STOP) {
            haltImport(awaitCancellation = true, joinTimeoutMs = USER_STOP_JOIN_TIMEOUT_MS)
            if (!container.importManager.progress.value.running) {
                stopImport()
            }
            return START_NOT_STICKY
        }

        val pending = container.importManager.takePendingForegroundImport()
        if (pending == null) {
            if (importJob?.isActive != true && !container.importManager.progress.value.running) {
                container.importManager.abortForegroundReservation()
                stopImport()
            }
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            ImportNotifications.SERVICE_NOTIFICATION_ID,
            ImportNotifications.build(this, stopIntent = stopPendingIntent()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )

        val sessionId = session.incrementAndGet()
        importJob?.cancel()
        importJob = scope.launch {
            try {
                container.importManager.run(
                    request = pending.request,
                    overwriteCopyPath = pending.overwriteCopyPath,
                    consumeForegroundReservation = true,
                ) { current, total ->
                    if (current == 1 || current == total || current % 25 == 0) {
                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.notify(
                            ImportNotifications.SERVICE_NOTIFICATION_ID,
                            ImportNotifications.build(
                                this@ImportForegroundService,
                                current,
                                total,
                                stopPendingIntent(),
                            ),
                        )
                    }
                }
            } finally {
                if (session.get() == sessionId) {
                    stopImport()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        haltImport(awaitCancellation = true, joinTimeoutMs = SYSTEM_HALT_JOIN_TIMEOUT_MS)
        scope.cancel()
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        session.incrementAndGet()
        haltImport(awaitCancellation = true, joinTimeoutMs = SYSTEM_HALT_JOIN_TIMEOUT_MS)
        stopImport()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Cancel the running import and wait for [ImportManager.run] to finish.
     * Does not drop the foreground service while `progress.running` is still true;
     * [abortForegroundReservation] only clears the lock after `run()` has left that state.
     */
    private fun haltImport(awaitCancellation: Boolean, joinTimeoutMs: Long) {
        val running = importJob
        importJob = null
        running?.cancel()
        if (running != null && awaitCancellation) {
            runBlocking {
                withTimeoutOrNull(joinTimeoutMs) { running.join() }
            }
        }
        (application as? SolarMonitorApplication)?.container?.importManager
            ?.abortForegroundReservation()
    }

    private fun stopImport() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopPendingIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            2,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val ACTION_STOP = "com.alorbach.solarmonitor.action.STOP_IMPORT"
        private const val SYSTEM_HALT_JOIN_TIMEOUT_MS = 3_000L
        private const val USER_STOP_JOIN_TIMEOUT_MS = 30_000L

        fun startIntent(context: Context): Intent =
            Intent(context, ImportForegroundService::class.java)

        fun stopIntent(context: Context): Intent =
            Intent(context, ImportForegroundService::class.java).setAction(ACTION_STOP)
    }
}
