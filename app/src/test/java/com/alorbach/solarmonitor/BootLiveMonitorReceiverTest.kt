package com.alorbach.solarmonitor

import android.content.Intent
import com.alorbach.solarmonitor.service.BootLiveMonitorReceiver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootLiveMonitorReceiverTest {
    @Test
    fun shouldRestartOnlyWhenDeviceIdsPersistedAndBluetoothGranted() {
        assertFalse(BootLiveMonitorReceiver.shouldRestartLiveMonitor(longArrayOf(), bluetoothConnectGranted = true))
        assertFalse(BootLiveMonitorReceiver.shouldRestartLiveMonitor(longArrayOf(1L), bluetoothConnectGranted = false))
        assertTrue(BootLiveMonitorReceiver.shouldRestartLiveMonitor(longArrayOf(1L), bluetoothConnectGranted = true))
    }

    @Test
    fun restartActionsIncludeBootAndPackageReplace() {
        assertTrue(BootLiveMonitorReceiver.isRestartAction(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(BootLiveMonitorReceiver.isRestartAction(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(BootLiveMonitorReceiver.isRestartAction(BootLiveMonitorReceiver.ACTION_QUICKBOOT_POWERON))
        assertFalse(BootLiveMonitorReceiver.isRestartAction(Intent.ACTION_SCREEN_ON))
    }
}
