package com.alorbach.solarmonitor.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.alorbach.solarmonitor.SolarMonitorApplication
import com.alorbach.solarmonitor.domain.LivePollWindow
import com.alorbach.solarmonitor.domain.parseZoneId
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first

data class LivePollPolicy(
    val startMinutes: Int,
    val endMinutes: Int,
    val zones: Map<Long, ZoneId>,
) {
    fun zoneFor(deviceId: Long): ZoneId =
        zones[deviceId] ?: parseZoneId(null)

    fun isOpen(deviceId: Long, now: Instant = Instant.now()): Boolean =
        LivePollWindow.isOpen(now, startMinutes, endMinutes, zoneFor(deviceId))

    fun anyOpen(deviceIds: LongArray, now: Instant = Instant.now()): Boolean =
        deviceIds.any { isOpen(it, now) }

    fun millisUntilOpen(deviceId: Long, now: Instant = Instant.now()): Long =
        LivePollWindow.millisUntilNextOpen(now, startMinutes, endMinutes, zoneFor(deviceId))

    fun millisUntilClose(deviceId: Long, now: Instant = Instant.now()): Long =
        LivePollWindow.millisUntilClose(now, startMinutes, endMinutes, zoneFor(deviceId))

    fun earliestNextOpenEpochMillis(deviceIds: LongArray, now: Instant = Instant.now()): Long {
        val wait = deviceIds.minOfOrNull { millisUntilOpen(it, now) }
            ?: LivePollWindow.millisUntilNextOpen(now, startMinutes, endMinutes, parseZoneId(null))
        return now.toEpochMilli() + wait
    }

    fun earliestFollowingWindowOpenEpochMillis(
        deviceIds: LongArray,
        now: Instant = Instant.now(),
    ): Long {
        val dayMillis = 24 * 60 * 60 * 1_000L
        return deviceIds.minOfOrNull { deviceId ->
            if (!isOpen(deviceId, now)) {
                now.toEpochMilli() + millisUntilOpen(deviceId, now)
            } else {
                val untilClose = millisUntilClose(deviceId, now)
                if (untilClose == Long.MAX_VALUE) {
                    now.toEpochMilli() + dayMillis
                } else {
                    val afterClose = now.plusMillis(untilClose + 1_000L)
                    afterClose.toEpochMilli() + millisUntilOpen(deviceId, afterClose)
                }
            }
        } ?: now.toEpochMilli() + dayMillis
    }
}

object LivePollScheduler {
    const val ACTION_RESUME = "com.alorbach.solarmonitor.action.LIVE_POLL_WINDOW"
    private const val TAG = "LivePollScheduler"
    private const val REQUEST_CODE = 5001
    private const val MIN_DELAY_MS = 5_000L
    private const val RETRY_DELAY_MS = 15 * 60_000L

    fun cancel(context: Context) {
        alarmManager(context).cancel(pendingIntent(context))
    }

    fun scheduleAt(context: Context, triggerAtMillis: Long) {
        val at = triggerAtMillis.coerceAtLeast(System.currentTimeMillis() + MIN_DELAY_MS)
        val am = alarmManager(context)
        val pi = pendingIntent(context)
        // Inexact only: avoids SCHEDULE_EXACT_ALARM / Play special-access review.
        // Doze and OEM battery policies may delay the window resume by several minutes.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    suspend fun loadPolicy(context: Context, deviceIds: LongArray): LivePollPolicy {
        val app = context.applicationContext as SolarMonitorApplication
        val settings = app.container.settingsStore.settings.first()
        val zones = deviceIds.associateWith { id ->
            parseZoneId(app.container.repository.getDevice(id)?.timezone)
        }
        return LivePollPolicy(
            startMinutes = settings.livePollWindowStartMinutes,
            endMinutes = settings.livePollWindowEndMinutes,
            zones = zones,
        )
    }

    suspend fun anyDeviceInWindow(context: Context, deviceIds: LongArray): Boolean {
        if (deviceIds.isEmpty()) return false
        return loadPolicy(context, deviceIds).anyOpen(deviceIds)
    }

    suspend fun scheduleResume(context: Context, deviceIds: LongArray) {
        if (deviceIds.isEmpty()) {
            cancel(context)
            return
        }
        val policy = loadPolicy(context, deviceIds)
        scheduleAt(context, policy.earliestNextOpenEpochMillis(deviceIds))
    }

    /** Retry an FGS start that Android blocked from a background alarm/boot path. */
    fun scheduleRetry(context: Context, delayMs: Long = RETRY_DELAY_MS) {
        scheduleAt(context, System.currentTimeMillis() + delayMs)
    }

    enum class FgsStartPolicy {
        ALLOW_BACKGROUND_START,
        NOTIFICATION_ONLY,
    }

    /**
     * Single owner for resume: re-reads persisted IDs before and after the suspending
     * window check so a user Stop is never undone by a stale start.
     */
    suspend fun attemptResume(context: Context, startPolicy: FgsStartPolicy) {
        val appContext = context.applicationContext
        val alreadyActive = (appContext as? SolarMonitorApplication)
            ?.container
            ?.liveMonitoringRepository
            ?.state
            ?.value
            ?.active == true
        if (alreadyActive) return
        val ids = LiveMonitorService.persistedDeviceIds(appContext)
        val bluetoothOk = BootLiveMonitorReceiver.hasBluetoothConnectPermission(appContext)
        if (!BootLiveMonitorReceiver.shouldRestartLiveMonitor(ids, bluetoothOk)) {
            cancel(appContext)
            return
        }
        // Suspending window/policy load; re-read IDs afterward so a concurrent Stop wins.
        anyDeviceInWindow(appContext, ids)
        val freshIds = LiveMonitorService.persistedDeviceIds(appContext)
        val freshBt = BootLiveMonitorReceiver.hasBluetoothConnectPermission(appContext)
        if (!BootLiveMonitorReceiver.shouldRestartLiveMonitor(freshIds, freshBt)) {
            cancel(appContext)
            return
        }
        val inWindow = anyDeviceInWindow(appContext, freshIds)
        when {
            BootLiveMonitorReceiver.shouldScheduleWindowResume(freshIds, freshBt, inWindow) -> {
                scheduleResume(appContext, freshIds)
            }
            BootLiveMonitorReceiver.shouldStartForeground(freshIds, freshBt, inWindow) -> {
                if (startPolicy == FgsStartPolicy.NOTIFICATION_ONLY && !isAppVisible()) {
                    LiveResumeNotifier.post(appContext)
                    scheduleFollowingWindowResume(appContext, freshIds)
                    return
                }
                try {
                    ContextCompat.startForegroundService(
                        appContext,
                        LiveMonitorService.resumeIntent(appContext),
                    )
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Live monitor FGS start blocked; retry later", error)
                    scheduleRetry(appContext)
                }
            }
        }
    }

    private suspend fun scheduleFollowingWindowResume(context: Context, deviceIds: LongArray) {
        val policy = loadPolicy(context, deviceIds)
        scheduleAt(context, policy.earliestFollowingWindowOpenEpochMillis(deviceIds))
    }

    private fun isAppVisible(): Boolean {
        return ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
    }

    suspend fun syncAfterSettingsChange(context: Context) {
        val appContext = context.applicationContext
        val genAtDecision = LiveMonitorService.currentRunGeneration()
        val ids = LiveMonitorService.persistedDeviceIds(appContext)
        if (ids.isEmpty()) {
            cancel(appContext)
            return
        }
        val bluetoothOk = BootLiveMonitorReceiver.hasBluetoothConnectPermission(appContext)
        anyDeviceInWindow(appContext, ids)
        val freshIds = LiveMonitorService.persistedDeviceIds(appContext)
        val freshBt = BootLiveMonitorReceiver.hasBluetoothConnectPermission(appContext)
        if (freshIds.isEmpty() || !freshBt) {
            cancel(appContext)
            return
        }
        val inWindow = anyDeviceInWindow(appContext, freshIds)
        if (inWindow) {
            cancel(appContext)
            try {
                ContextCompat.startForegroundService(
                    appContext,
                    LiveMonitorService.resumeIntent(appContext),
                )
            } catch (error: RuntimeException) {
                Log.w(TAG, "Settings sync FGS start blocked; retry later", error)
                scheduleRetry(appContext)
            }
        } else {
            scheduleResume(appContext, freshIds)
            val active = (appContext as? SolarMonitorApplication)
                ?.container
                ?.liveMonitoringRepository
                ?.state
                ?.value
                ?.active == true
            if (active && LiveMonitorService.currentRunGeneration() == genAtDecision) {
                appContext.startService(
                    LiveMonitorService.pauseIntent(appContext)
                        .putExtra(LiveMonitorService.EXTRA_RUN_GENERATION, genAtDecision),
                )
            }
        }
    }

    private fun alarmManager(context: Context): AlarmManager =
        context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(context: Context): PendingIntent {
        val appContext = context.applicationContext
        return PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            Intent(appContext, LivePollWindowReceiver::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

}
