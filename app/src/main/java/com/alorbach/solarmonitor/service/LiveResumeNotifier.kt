package com.alorbach.solarmonitor.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.alorbach.solarmonitor.R

/** Presents the user action required when an inexact alarm cannot start the live FGS. */
object LiveResumeNotifier {
    private const val CHANNEL_ID = "live_monitor_resume"
    private const val NOTIFICATION_ID = 4004
    private const val PENDING_INTENT_REQUEST = 5002

    fun post(context: Context): Boolean {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    appContext.getString(R.string.live_monitor_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            if (manager.getNotificationChannel(CHANNEL_ID)?.importance ==
                NotificationManager.IMPORTANCE_NONE
            ) {
                return false
            }
        }
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return false

        val openApp = PendingIntent.getBroadcast(
            appContext,
            PENDING_INTENT_REQUEST,
            Intent(appContext, LiveResumeReceiver::class.java)
                .setAction(LiveResumeReceiver.ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(appContext.getString(R.string.live_monitor_resume_title))
            .setContentText(appContext.getString(R.string.live_monitor_resume_body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        return runCatching {
            manager.notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }
}
