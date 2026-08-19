package com.alorbach.solarmonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.alorbach.solarmonitor.MainActivity
import com.alorbach.solarmonitor.R

object ImportNotifications {
    const val CHANNEL_ID = "data_import"
    const val SERVICE_NOTIFICATION_ID = 4002
    const val WORKER_NOTIFICATION_ID = 4003

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.import_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    fun build(
        context: Context,
        current: Int = 0,
        total: Int = 0,
        stopIntent: PendingIntent? = null,
    ): Notification {
        val text = if (total > 0) {
            context.getString(R.string.import_notification_progress, current, total)
        } else {
            context.getString(R.string.import_in_progress)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.import_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        if (total > 0) {
            builder.setProgress(total, current, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        if (stopIntent != null) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.stop),
                stopIntent,
            )
        }
        return builder.build()
    }

    fun foregroundInfo(context: Context, current: Int, total: Int): ForegroundInfo {
        ensureChannel(context)
        val notification = build(context, current, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                WORKER_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(WORKER_NOTIFICATION_ID, notification)
        }
    }
}
