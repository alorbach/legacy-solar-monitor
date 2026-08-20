package com.alorbach.solarmonitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Restarts live monitoring after reboot or app update when device IDs were persisted
 * (cleared only when the user stops the monitor).
 */
class BootLiveMonitorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!isRestartAction(action)) return
        val appContext = context.applicationContext
        val deviceIds = LiveMonitorService.persistedDeviceIds(appContext)
        if (!shouldRestartLiveMonitor(deviceIds)) return

        val pending = goAsync()
        val delayMs = if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            0L
        } else {
            BLUETOOTH_READY_DELAY_MS
        }
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                ContextCompat.startForegroundService(
                    appContext,
                    LiveMonitorService.startIntent(appContext, deviceIds),
                )
            } finally {
                pending.finish()
            }
        }, delayMs)
    }

    companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
        const val BLUETOOTH_READY_DELAY_MS = 8_000L

        fun isRestartAction(action: String): Boolean = when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT_POWERON,
            ACTION_HTC_QUICKBOOT_POWERON,
            -> true
            else -> false
        }

        fun shouldRestartLiveMonitor(deviceIds: LongArray): Boolean = deviceIds.isNotEmpty()
    }
}
