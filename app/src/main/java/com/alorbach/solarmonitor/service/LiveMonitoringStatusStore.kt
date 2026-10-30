package com.alorbach.solarmonitor.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LiveMonitoringPauseReason {
    STOPPED, RESUME_PENDING, WAITING_FOR_WINDOW, WAITING_FOR_WIFI,
    NEEDS_RESUME, NOTIFICATIONS_DISABLED, BLUETOOTH_PERMISSION_REQUIRED, START_FAILED,
}

/** Persistent diagnostic state, never evidence that a foreground service is still running. */
data class LiveMonitoringStatus(
    val reason: LiveMonitoringPauseReason = LiveMonitoringPauseReason.STOPPED,
    val nextAttemptAtMillis: Long? = null,
    val lastSuccessfulPollAtMillis: Long? = null,
)

object LiveMonitoringStatusStore {
    private const val PREFS = "live_monitor_status"
    private const val REASON = "reason"
    private const val NEXT = "next_attempt"
    private const val SUCCESS = "last_success"
    private var observed: MutableStateFlow<LiveMonitoringStatus>? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        synchronized(this) { observed?.value = read(prefs) }
    }

    @Synchronized
    fun observe(context: Context): StateFlow<LiveMonitoringStatus> {
        val prefs = preferences(context)
        val flow = observed ?: MutableStateFlow(read(prefs)).also {
            observed = it
            // App-process lifetime listener; the application context is the only context retained.
            prefs.registerOnSharedPreferenceChangeListener(listener)
        }
        return flow.asStateFlow()
    }

    fun snapshot(context: Context): LiveMonitoringStatus = read(preferences(context))

    fun update(context: Context, reason: LiveMonitoringPauseReason, nextAttemptAtMillis: Long? = null) {
        // The same monitor protects persisted request changes: an old async resume cannot
        // overwrite a user's Stop with a stale paused/error status.
        synchronized(LiveMonitorService) {
            val requested = LiveMonitorService.persistedDeviceIds(context).isNotEmpty()
            val effectiveReason = if (requested) reason else LiveMonitoringPauseReason.STOPPED
            val prefs = preferences(context)
            prefs.edit().putString(REASON, effectiveReason.name).apply {
                if (!requested || nextAttemptAtMillis == null) remove(NEXT)
                else putLong(NEXT, nextAttemptAtMillis)
            }.apply()
            synchronized(this) { observed?.value = read(prefs) }
        }
    }

    fun recordSuccessfulPoll(context: Context, atMillis: Long = System.currentTimeMillis()) {
        val prefs = preferences(context)
        prefs.edit().putLong(SUCCESS, atMillis).apply()
        synchronized(this) { observed?.value = read(prefs) }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(prefs: SharedPreferences) = LiveMonitoringStatus(
        reason = runCatching {
            LiveMonitoringPauseReason.valueOf(prefs.getString(REASON, null).orEmpty())
        }.getOrDefault(LiveMonitoringPauseReason.STOPPED),
        nextAttemptAtMillis = prefs.getLong(NEXT, 0L).takeIf { it > 0L },
        lastSuccessfulPollAtMillis = prefs.getLong(SUCCESS, 0L).takeIf { it > 0L },
    )
}
