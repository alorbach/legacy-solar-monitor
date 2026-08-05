package com.alorbach.solarmonitor.data

import android.content.Context
import com.alorbach.solarmonitor.data.cloud.GoogleCloudStorageBackupRepository
import com.alorbach.solarmonitor.data.importing.ImportManager
import com.alorbach.solarmonitor.data.importing.LegacySbfspotImporters
import com.alorbach.solarmonitor.data.local.SolarMonitorDatabase
import com.alorbach.solarmonitor.data.repository.LiveMonitoringRepository
import com.alorbach.solarmonitor.data.repository.SolarRepository
import com.alorbach.solarmonitor.data.settings.AppSettingsStore
import com.alorbach.solarmonitor.device.SmaLegacyBluetoothGateway
import com.alorbach.solarmonitor.device.SmaLegacyBluetoothGatewayImpl
import com.alorbach.solarmonitor.domain.ReportExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsStore = AppSettingsStore(appContext)
    val database = SolarMonitorDatabase.create(appContext)
    val bluetoothGateway: SmaLegacyBluetoothGateway = SmaLegacyBluetoothGatewayImpl(appContext)
    val reportExporter = ReportExporter(appContext)
    val cloudBackupRepository = GoogleCloudStorageBackupRepository(appContext, settingsStore)
    val repository = SolarRepository(
        appContext = appContext,
        db = database,
        settingsStore = settingsStore,
    )
    val importManager = ImportManager(
        appContext = appContext,
        repository = repository,
        importers = LegacySbfspotImporters(appContext, repository),
    )
    val liveMonitoringRepository = LiveMonitoringRepository(
        repository = repository,
        bluetoothGateway = bluetoothGateway,
        scope = applicationScope,
    )
}
