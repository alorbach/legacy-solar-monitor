package com.alorbach.solarmonitor

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.alorbach.solarmonitor.BuildConfig
import com.alorbach.solarmonitor.data.AppContainer
import com.alorbach.solarmonitor.data.cloud.BackupTrigger
import com.alorbach.solarmonitor.data.cloud.CloudBackupPolicy
import com.alorbach.solarmonitor.data.importing.ImportRequest
import com.alorbach.solarmonitor.data.importing.canReplay
import com.alorbach.solarmonitor.data.importing.replayConfig
import com.alorbach.solarmonitor.data.model.DailyPoint
import com.alorbach.solarmonitor.data.model.DeviceDashboardSummary
import com.alorbach.solarmonitor.data.model.DeviceProfileEntity
import com.alorbach.solarmonitor.data.model.DeviceTransport
import com.alorbach.solarmonitor.data.model.ImportJobEntity
import com.alorbach.solarmonitor.data.model.ImportJobStatus
import com.alorbach.solarmonitor.data.model.PortfolioSummary
import com.alorbach.solarmonitor.data.model.TariffPeriodEntity
import com.alorbach.solarmonitor.data.settings.AppSettings
import com.alorbach.solarmonitor.device.BluetoothDeviceDescriptor
import com.alorbach.solarmonitor.domain.YieldFormatting
import com.alorbach.solarmonitor.i18n.LocaleController
import com.alorbach.solarmonitor.service.LiveMonitorService
import com.alorbach.solarmonitor.service.LivePollScheduler
import com.alorbach.solarmonitor.ui.DashboardTab
import com.alorbach.solarmonitor.ui.DevicesTab
import com.alorbach.solarmonitor.ui.ImportTab
import com.alorbach.solarmonitor.ui.SettingsTab
import com.alorbach.solarmonitor.ui.StatisticsScreen
import com.alorbach.solarmonitor.ui.createDeviceFromBluetooth
import com.alorbach.solarmonitor.ui.preferredBluetoothSeed
import com.alorbach.solarmonitor.ui.theme.SolarDarkColors
import com.alorbach.solarmonitor.ui.theme.SolarLightColors
import com.alorbach.solarmonitor.work.ScheduledImportWorker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import kotlin.coroutines.cancellation.CancellationException

class MainActivity : ComponentActivity() {
    private val container: AppContainer by lazy {
        (application as SolarMonitorApplication).container
    }

    val permissionBannerState = mutableStateOf<String?>(null)
    val permissionBannerActionState = mutableStateOf(BannerAction.APP_SETTINGS)

    fun setPermissionBanner(message: String?, action: BannerAction = BannerAction.APP_SETTINGS) {
        permissionBannerState.value = message
        permissionBannerActionState.value = action
    }

    @Volatile
    private var pendingPermissionWork: PendingPermissionWork? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { granted -> !granted }.keys
        setPermissionBanner(
            when {
                denied.isEmpty() -> null
                denied.any {
                    it == Manifest.permission.BLUETOOTH_CONNECT ||
                        it == Manifest.permission.BLUETOOTH_SCAN ||
                        it == Manifest.permission.ACCESS_FINE_LOCATION
                } -> getString(R.string.permissions_denied)
                else -> null
            }
        )
        val pending = pendingPermissionWork
        pendingPermissionWork = null
        if (denied.isEmpty() && pending != null) {
            resumePendingPermissionWork(pending)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleController.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPermissionWork = PendingPermissionWork.fromBundle(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (darkTheme) SolarDarkColors else SolarLightColors) {
                SolarMonitorApp(container = container, activity = this)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingPermissionWork?.writeTo(outState)
    }

    fun ensureBluetoothScanPermissions(): Boolean {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isEmpty()) {
            startBluetoothScan()
            return true
        }
        pendingPermissionWork = PendingPermissionWork.Scan
        permissionLauncher.launch(permissions.toTypedArray())
        return false
    }

    fun ensureLiveMonitorPermissions(deviceIds: LongArray): Boolean {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isEmpty()) {
            startLiveMonitor(deviceIds)
            return true
        }
        pendingPermissionWork = PendingPermissionWork.LiveStart(deviceIds)
        permissionLauncher.launch(permissions.toTypedArray())
        return false
    }

    fun ensureNotificationPermissionForWarnings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            enableInverterWarnings()
            return true
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            enableInverterWarnings()
            return true
        }
        pendingPermissionWork = PendingPermissionWork.EnableWarnings
        permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        return false
    }

    private fun resumePendingPermissionWork(pending: PendingPermissionWork) {
        when (pending) {
            PendingPermissionWork.Scan -> startBluetoothScan()
            is PendingPermissionWork.LiveStart -> startLiveMonitor(pending.deviceIds)
            PendingPermissionWork.EnableWarnings -> enableInverterWarnings()
        }
    }

    fun startBluetoothScan() {
        if (!isBluetoothEnabled()) {
            setPermissionBanner(
                getString(R.string.bluetooth_disabled),
                BannerAction.BLUETOOTH_SETTINGS,
            )
            return
        }
        setPermissionBanner(null)
        when (container.bluetoothGateway.startDiscovery()) {
            null -> setPermissionBanner(null)
            "location_disabled" ->
                setPermissionBanner(
                    getString(R.string.location_required_for_scan),
                    BannerAction.LOCATION_SETTINGS,
                )
            "location_precise_required" ->
                setPermissionBanner(getString(R.string.location_precise_required))
            "missing_permission" ->
                setPermissionBanner(getString(R.string.permissions_denied))
            "bluetooth_disabled" ->
                setPermissionBanner(
                    getString(R.string.bluetooth_disabled),
                    BannerAction.BLUETOOTH_SETTINGS,
                )
            else ->
                setPermissionBanner(getString(R.string.bluetooth_scan_failed))
        }
    }

    private fun startLiveMonitor(deviceIds: LongArray) {
        if (deviceIds.isEmpty()) return
        ContextCompat.startForegroundService(
            this,
            LiveMonitorService.startIntent(this, deviceIds),
        )
    }

    private fun enableInverterWarnings() {
        lifecycleScope.launch {
            container.settingsStore.update { it.copy(inverterWarningAlertsEnabled = true) }
        }
    }

    fun isBluetoothEnabled(): Boolean {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return adapter?.isEnabled == true
    }
}

enum class BannerAction { APP_SETTINGS, LOCATION_SETTINGS, BLUETOOTH_SETTINGS }

private sealed class PendingPermissionWork {
    data object Scan : PendingPermissionWork()
    data class LiveStart(val deviceIds: LongArray) : PendingPermissionWork()
    data object EnableWarnings : PendingPermissionWork()

    fun writeTo(outState: Bundle) {
        when (this) {
            Scan -> outState.putString(KEY_PENDING, VALUE_SCAN)
            is LiveStart -> {
                outState.putString(KEY_PENDING, VALUE_LIVE)
                outState.putLongArray(KEY_LIVE_IDS, deviceIds)
            }
            EnableWarnings -> outState.putString(KEY_PENDING, VALUE_WARNINGS)
        }
    }

    companion object {
        private const val KEY_PENDING = "pending_permission_work"
        private const val KEY_LIVE_IDS = "pending_live_ids"
        private const val VALUE_SCAN = "scan"
        private const val VALUE_LIVE = "live"
        private const val VALUE_WARNINGS = "warnings"

        fun fromBundle(state: Bundle?): PendingPermissionWork? = when (state?.getString(KEY_PENDING)) {
            VALUE_SCAN -> Scan
            VALUE_LIVE -> LiveStart(state.getLongArray(KEY_LIVE_IDS) ?: longArrayOf())
            VALUE_WARNINGS -> EnableWarnings
            else -> null
        }
    }
}

private enum class AppTab { DASHBOARD, STATISTICS, DEVICES, IMPORT, SETTINGS }

@Composable
private fun SolarMonitorApp(container: AppContainer, activity: MainActivity) {
    val scope = rememberCoroutineScope()
    var currentTab by rememberSaveable { mutableStateOf(AppTab.DASHBOARD) }
    val devices by container.repository.observeDevices().collectAsStateWithLifecycle(initialValue = emptyList())
    val importJobs by container.repository.observeImportJobs().collectAsStateWithLifecycle(initialValue = emptyList())
    val liveState by container.liveMonitoringRepository.state.collectAsStateWithLifecycle()
    val bluetoothDevices by container.bluetoothGateway.discoveredDevices.collectAsStateWithLifecycle()
    val isScanning by container.bluetoothGateway.isDiscovering.collectAsStateWithLifecycle()
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var localDataEpoch by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        // Activity context can start the FGS when a background alarm was blocked overnight.
        LivePollScheduler.attemptResume(activity)
    }
    val importJobsFingerprint = remember(importJobs) {
        importJobs.joinToString(";") { job ->
            "${job.id}:${job.status}:${job.completedAtEpochSeconds ?: job.createdAtEpochSeconds}"
        }
    }
    val dataRevision = remember(localDataEpoch, importJobsFingerprint) {
        localDataEpoch + importJobsFingerprint.hashCode().toLong()
    }
    val portfolioRefreshKey = remember(devices, importJobsFingerprint, localDataEpoch) {
        buildString {
            append(localDataEpoch)
            append('|')
            append(importJobsFingerprint)
            append('|')
            devices.forEach {
                append(it.id).append(':')
                    .append(it.lastLiveReadAtEpochSeconds ?: 0).append(':')
                    .append(it.lastArchiveSyncAtEpochSeconds ?: 0).append(':')
                    .append(it.lastConnectionStatus.orEmpty()).append('|')
            }
        }
    }
    val portfolio by produceState(
        initialValue = PortfolioSummary(0, null, 0, 0, 0, 0.0, null),
        key1 = portfolioRefreshKey,
    ) {
        value = container.repository.getPortfolioSummary()
    }
    val colors = MaterialTheme.colorScheme
    val permissionMessage by activity.permissionBannerState
    val bannerAction by activity.permissionBannerActionState
    var pendingScrollDeviceId by remember { mutableStateOf<Long?>(null) }
    val assignedMacs = remember(devices) {
        devices.mapNotNull { it.btMac?.takeIf(String::isNotBlank)?.uppercase() }.toSet()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.background,
    ) {
        Scaffold(
            containerColor = colors.background,
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .background(
                            Brush.verticalGradient(
                                listOf(colors.secondary, colors.secondary.copy(alpha = 0.55f), colors.background)
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                ) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.onBackground,
                        maxLines = 1,
                    )
                    Text(
                        when (currentTab) {
                            AppTab.DASHBOARD -> stringResource(R.string.tab_dashboard)
                            AppTab.STATISTICS -> stringResource(R.string.tab_statistics)
                            AppTab.DEVICES -> stringResource(R.string.tab_devices)
                            AppTab.IMPORT -> stringResource(R.string.tab_import)
                            AppTab.SETTINGS -> stringResource(R.string.tab_settings)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    permissionMessage?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val intent = when (bannerAction) {
                                    BannerAction.LOCATION_SETTINGS ->
                                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

                                    BannerAction.BLUETOOTH_SETTINGS ->
                                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

                                    BannerAction.APP_SETTINGS ->
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", activity.packageName, null)
                                        }
                                }
                                activity.startActivity(intent)
                            },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = colors.errorContainer,
                                contentColor = colors.onErrorContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = colors.surface) {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            alwaysShowLabel = false,
                            icon = {
                                when (tab) {
                                    AppTab.DASHBOARD -> Icon(Icons.Rounded.Dashboard, contentDescription = null)
                                    AppTab.STATISTICS -> Icon(Icons.Rounded.BarChart, contentDescription = null)
                                    AppTab.DEVICES -> Icon(Icons.Rounded.Devices, contentDescription = null)
                                    AppTab.IMPORT -> Icon(Icons.Rounded.FileDownload, contentDescription = null)
                                    AppTab.SETTINGS -> Icon(Icons.Rounded.Settings, contentDescription = null)
                                }
                            },
                            label = {
                                Text(
                                    when (tab) {
                                        AppTab.DASHBOARD -> stringResource(R.string.tab_dashboard)
                                        AppTab.STATISTICS -> stringResource(R.string.tab_statistics)
                                        AppTab.DEVICES -> stringResource(R.string.tab_devices)
                                        AppTab.IMPORT -> stringResource(R.string.tab_import)
                                        AppTab.SETTINGS -> stringResource(R.string.tab_settings)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            },
            floatingActionButton = {
                if (currentTab == AppTab.DEVICES) {
                    FloatingActionButton(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                        onClick = {
                            scope.launch {
                                val seed = preferredBluetoothSeed(bluetoothDevices, assignedMacs)
                                    ?: preferredBluetoothSeed(
                                        container.bluetoothGateway.listBondedDevices(),
                                        assignedMacs,
                                    )
                                pendingScrollDeviceId = createDeviceFromBluetooth(
                                    container = container,
                                    seed = seed,
                                    existingCount = devices.size,
                                    context = activity,
                                )
                                localDataEpoch += 1
                            }
                        },
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.add_device))
                    }
                }
            },
        ) { padding ->
            val tabModifier = Modifier.padding(padding).imePadding()
            when (currentTab) {
                AppTab.DASHBOARD -> DashboardTab(
                    modifier = tabModifier,
                    portfolio = portfolio,
                    devices = devices,
                    container = container,
                    liveMessage = liveState.message,
                    liveActive = liveState.active,
                    liveActiveDeviceIds = liveState.activeDeviceIds,
                    dataEpoch = dataRevision,
                    onStartLive = { deviceIds ->
                        activity.ensureLiveMonitorPermissions(deviceIds.toLongArray())
                    },
                    onStopLive = {
                        activity.startService(LiveMonitorService.stopIntent(activity))
                    },
                )

                AppTab.STATISTICS -> StatisticsScreen(
                    modifier = tabModifier,
                    devices = devices,
                    container = container,
                    settings = settings,
                    dataEpoch = dataRevision,
                )

                AppTab.DEVICES -> DevicesTab(
                    modifier = tabModifier,
                    devices = devices,
                    container = container,
                    bluetoothDevices = bluetoothDevices,
                    isScanning = isScanning,
                    bluetoothEnabled = activity.isBluetoothEnabled(),
                    onRefreshBluetooth = {
                        if (!activity.isBluetoothEnabled()) {
                            activity.setPermissionBanner(
                                activity.getString(R.string.bluetooth_disabled),
                                BannerAction.BLUETOOTH_SETTINGS,
                            )
                        } else {
                            activity.ensureBluetoothScanPermissions()
                        }
                    },
                    onStopBluetooth = { container.bluetoothGateway.stopDiscovery() },
                    onDataChanged = { localDataEpoch += 1 },
                    assignedMacs = assignedMacs,
                    scrollToDeviceId = pendingScrollDeviceId,
                    onRequestScrollToDevice = { pendingScrollDeviceId = it },
                    onScrollHandled = { pendingScrollDeviceId = null },
                    monitoredDeviceIds = liveState.activeDeviceIds,
                    dataEpoch = dataRevision,
                    onDeleteDevice = { deviceId ->
                        scope.launch {
                            container.liveMonitoringRepository.stopDevice(deviceId)
                            val persisted = LiveMonitorService.persistedDeviceIds(activity)
                            if (deviceId in persisted) {
                                val remaining = persisted.filter { it != deviceId }.toLongArray()
                                LiveMonitorService.persistDeviceIds(activity, remaining)
                                if (remaining.isEmpty()) {
                                    activity.startService(LiveMonitorService.stopIntent(activity))
                                } else {
                                    ContextCompat.startForegroundService(
                                        activity,
                                        LiveMonitorService.startIntent(activity, remaining),
                                    )
                                }
                            }
                            container.repository.deleteDevice(deviceId)
                        }
                    },
                )

                AppTab.IMPORT -> ImportTab(
                    modifier = tabModifier,
                    devices = devices,
                    importJobs = importJobs,
                    container = container,
                    onDataChanged = { localDataEpoch += 1 },
                )

                AppTab.SETTINGS -> SettingsTab(
                    modifier = tabModifier,
                    container = container,
                    settings = settings,
                    devices = devices,
                )
            }
        }
    }
}

