package com.alorbach.solarmonitor.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Restarts live monitoring after reboot or app update when device IDs were persisted
 * (cleared only when the user stops the monitor). Outside the poll window, schedules
 * an inexact alarm instead of starting the connected-device foreground service.
 */
class BootLiveMonitorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!isRestartAction(action)) return
        val appContext = context.applicationContext
        val deviceIds = LiveMonitorService.persistedDeviceIds(appContext)
        val bluetoothGranted = hasBluetoothConnectPermission(appContext)
        if (!shouldRestartLiveMonitor(deviceIds, bluetoothGranted)) return

        val pending = goAsync()
        val delayMs = if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            0L
        } else {
            BLUETOOTH_READY_DELAY_MS
        }
        scope.launch {
            try {
                delay(delayMs)
                LivePollScheduler.attemptResume(
                    appContext,
                    LivePollScheduler.FgsStartPolicy.ALLOW_BACKGROUND_START,
                )
            } catch (error: RuntimeException) {
                Log.w("BootLiveMonitor", "Delayed live monitor start failed", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
        const val BLUETOOTH_READY_DELAY_MS = 8_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun isRestartAction(action: String): Boolean = when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT_POWERON,
            ACTION_HTC_QUICKBOOT_POWERON,
            -> true
            else -> false
        }

        fun shouldRestartLiveMonitor(
            deviceIds: LongArray,
            bluetoothConnectGranted: Boolean,
        ): Boolean = deviceIds.isNotEmpty() && bluetoothConnectGranted

        fun shouldStartForeground(
            deviceIds: LongArray,
            bluetoothConnectGranted: Boolean,
            anyDeviceInWindow: Boolean,
        ): Boolean = shouldRestartLiveMonitor(deviceIds, bluetoothConnectGranted) && anyDeviceInWindow

        fun shouldScheduleWindowResume(
            deviceIds: LongArray,
            bluetoothConnectGranted: Boolean,
            anyDeviceInWindow: Boolean,
        ): Boolean = shouldRestartLiveMonitor(deviceIds, bluetoothConnectGranted) && !anyDeviceInWindow

        fun hasBluetoothConnectPermission(context: Context): Boolean {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Manifest.permission.BLUETOOTH_CONNECT
            } else {
                Manifest.permission.BLUETOOTH
            }
            return ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
}
