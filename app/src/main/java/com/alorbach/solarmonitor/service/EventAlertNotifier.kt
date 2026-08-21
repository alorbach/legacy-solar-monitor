package com.alorbach.solarmonitor.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.alorbach.solarmonitor.MainActivity
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import com.alorbach.solarmonitor.data.settings.AppSettingsStore
import com.alorbach.solarmonitor.domain.EventAlertPolicy
import com.alorbach.solarmonitor.domain.EventCatalog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EventAlertNotifier(
    private val context: Context,
    private val settingsStore: AppSettingsStore,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutex = Mutex()

    suspend fun onEventsSaved(
        events: List<DeviceEventEntity>,
        deviceName: (Long) -> String?,
    ) {
        if (events.isEmpty()) return
        val decision = mutex.withLock {
            val settings = settingsStore.settings.first()
            val evaluated = EventAlertPolicy.evaluate(
                incoming = events,
                watermarks = EventAlertPolicy.parseWatermarks(settings.eventAlertWatermarks),
                nowEpochSeconds = clock(),
                enabled = settings.inverterWarningAlertsEnabled,
            )
            if (evaluated.watermarks != EventAlertPolicy.parseWatermarks(settings.eventAlertWatermarks)) {
                settingsStore.update {
                    it.copy(eventAlertWatermarks = EventAlertPolicy.encodeWatermarks(evaluated.watermarks))
                }
            }
            evaluated
        }
        if (decision.notify.isEmpty()) return
        if (!canPostNotifications()) return
        ensureChannel()
        decision.notify.groupBy { it.deviceId }.forEach { (deviceId, deviceEvents) ->
            val first = deviceEvents.first()
            val title = eventTitle(first)
            val name = deviceName(deviceId) ?: context.getString(R.string.tab_devices)
            val text = if (deviceEvents.size == 1) {
                context.getString(R.string.event_alert_body, name, title)
            } else {
                context.getString(R.string.event_alert_more, name, title, deviceEvents.size - 1)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(
                notificationId(deviceId),
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_notify)
                    .setContentTitle(context.getString(R.string.event_alert_title))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            notificationId(deviceId),
                            Intent(context, MainActivity::class.java),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                    .build(),
            )
        }
    }

    private fun eventTitle(event: DeviceEventEntity): String {
        val known = EventCatalog.knownCodeLabelRes(event.eventCode)?.let { context.getString(it) }
        return known?.takeIf { it.isNotBlank() } ?: event.tag.ifBlank {
            context.getString(R.string.stats_event_code, event.eventCode)
        }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.event_alert_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    companion object {
        const val CHANNEL_ID = "inverter_warnings"
        private const val BASE_NOTIFICATION_ID = 4100

        fun notificationId(deviceId: Long): Int = BASE_NOTIFICATION_ID + (deviceId % 100_000).toInt()
    }
}
