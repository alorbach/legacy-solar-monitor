package com.alorbach.solarmonitor.data

import android.content.Context
import com.alorbach.solarmonitor.data.cloud.CloudBackupCoordinator
import com.alorbach.solarmonitor.data.cloud.GoogleDriveAuth
import com.alorbach.solarmonitor.data.cloud.GoogleDriveBackupRepository
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
    val googleDriveAuth = GoogleDriveAuth(appContext)
    val cloudBackupRepository = GoogleDriveBackupRepository(
        context = appContext,
        settingsStore = settingsStore,
        database = database,
        auth = googleDriveAuth,
    )
    val cloudBackupCoordinator = CloudBackupCoordinator(appContext)
    val eventAlertNotifier = com.alorbach.solarmonitor.service.EventAlertNotifier(appContext, settingsStore)
    val repository = SolarRepository(
        appContext = appContext,
        db = database,
        settingsStore = settingsStore,
        credentialStore = credentialStore,
        eventAlertNotifier = eventAlertNotifier,
    )
    val importers = LegacySbfspotImporters(appContext, repository)
    val importManager = ImportManager(
        appContext = appContext,
        repository = repository,
        importers = importers,
        credentialStore = credentialStore,
        cloudBackupCoordinator = cloudBackupCoordinator,
    )
    val liveMonitoringRepository = LiveMonitoringRepository(
        appContext = appContext,
        repository = repository,
        bluetoothGateway = bluetoothGateway,
        cloudBackupCoordinator = cloudBackupCoordinator,
    )

    fun close() {
        bluetoothGateway.release()
    }
}
