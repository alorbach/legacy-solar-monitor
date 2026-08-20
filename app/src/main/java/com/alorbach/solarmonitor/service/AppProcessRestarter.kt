package com.alorbach.solarmonitor.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.alorbach.solarmonitor.MainActivity
import kotlin.system.exitProcess

object AppProcessRestarter {
    fun restart(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        val pending = PendingIntent.getActivity(
            context,
            RESTART_REQUEST_CODE,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        )
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + RESTART_DELAY_MS, pending)
        exitProcess(0)
    }

    private const val RESTART_REQUEST_CODE = 7101
    private const val RESTART_DELAY_MS = 400L
}
