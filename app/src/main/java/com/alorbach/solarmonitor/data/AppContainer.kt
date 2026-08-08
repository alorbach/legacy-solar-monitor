package com.alorbach.solarmonitor.data

import android.content.Context
import com.alorbach.solarmonitor.data.cloud.GoogleCloudStorageBackupRepository
import com.alorbach.solarmonitor.data.importing.ImportManager
import com.alorbach.solarmonitor.data.importing.LegacySbfspotImporters
import com.alorbach.solarmonitor.data.local.SolarMonitorDatabase
import com.alorbach.solarmonitor.data.repository.LiveMonitoringRepository
import com.alorbach.solarmonitor.data.repository.SolarRepository
import com.alorbach.solarmonitor.data.security.CredentialStore
import com.alorbach.solarmonitor.data.settings.AppSettingsStore
import com.alorbach.solarmonitor.device.SmaLegacyBluetoothGateway
import com.alorbach.solarmonitor.device.SmaLegacyBluetoothGatewayImpl
import com.alorbach.solarmonitor.domain.ReportExporter

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val credentialStore = CredentialStore(appContext)
    val settingsStore = AppSettingsStore(appContext, credentialStore)
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
        credentialStore = credentialStore,
    )
    val liveMonitoringRepository = LiveMonitoringRepository(
        repository = repository,
        bluetoothGateway = bluetoothGateway,
    )

    fun close() {
        bluetoothGateway.release()
    }
}
