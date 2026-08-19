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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.graphics.Color
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
import com.alorbach.solarmonitor.ui.DashboardTab
import com.alorbach.solarmonitor.ui.DevicesTab
import com.alorbach.solarmonitor.ui.ImportTab
import com.alorbach.solarmonitor.ui.SettingsTab
import com.alorbach.solarmonitor.ui.StatisticsScreen
import com.alorbach.solarmonitor.ui.createDeviceFromBluetooth
import com.alorbach.solarmonitor.ui.preferredBluetoothSeed
import com.alorbach.solarmonitor.work.ScheduledImportWorker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private val SolarLightColors = lightColorScheme(
    primary = Color(0xFF17212B),
    onPrimary = Color(0xFFFFD86B),
    secondary = Color(0xFFF4B400),
    tertiary = Color(0xFF246B3D),
    onTertiary = Color(0xFFF4F0E8),
    tertiaryContainer = Color(0xFFD6EBD9),
    onTertiaryContainer = Color(0xFF0F3A1C),
    background = Color(0xFFF4F0E8),
    surface = Color(0xFFF8F5EE),
    surfaceVariant = Color(0xFFE4DED5),
    onBackground = Color(0xFF17212B),
    onSurface = Color(0xFF17212B),
    onSurfaceVariant = Color(0xFF5C636B),
    error = Color(0xFF8E2A2A),
    onError = Color(0xFFFFF8F6),
    errorContainer = Color(0xFFF5D6D6),
    onErrorContainer = Color(0xFF5C1414),
)

private val SolarDarkColors = darkColorScheme(
    primary = Color(0xFFFFD86B),
    onPrimary = Color(0xFF17212B),
    secondary = Color(0xFFF4B400),
    tertiary = Color(0xFF7BC794),
    onTertiary = Color(0xFF0F3A1C),
    tertiaryContainer = Color(0xFF1E4A2C),
    onTertiaryContainer = Color(0xFFD6EBD9),
    background = Color(0xFF121820),
    surface = Color(0xFF1B2430),
    surfaceVariant = Color(0xFF2A3442),
    onBackground = Color(0xFFF4F0E8),
    onSurface = Color(0xFFF4F0E8),
    onSurfaceVariant = Color(0xFFB8BFC7),
    error = Color(0xFFE08A8A),
    onError = Color(0xFF3A1010),
    errorContainer = Color(0xFF5C1414),
    onErrorContainer = Color(0xFFF5D6D6),
)

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
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleController.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissionsIfNeeded()
        setContent {
            val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (darkTheme) SolarDarkColors else SolarLightColors) {
                SolarMonitorApp(container = container, activity = this)
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            // Classic discovery of unpaired devices needs location on all current Android versions.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
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
        if (permissions.isEmpty()) return true
        permissionLauncher.launch(permissions.toTypedArray())
        return false
    }

    fun isBluetoothEnabled(): Boolean {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return adapter?.isEnabled == true
    }
}

enum class BannerAction { APP_SETTINGS, LOCATION_SETTINGS }

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
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.onBackground,
                        maxLines = 1,
                    )
                    Text(
                        stringResource(R.string.app_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    permissionMessage?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            message,
                            color = colors.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.errorContainer, RoundedCornerShape(12.dp))
                                .clickable {
                                    val intent = when (bannerAction) {
                                        BannerAction.LOCATION_SETTINGS ->
                                            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

                                        BannerAction.APP_SETTINGS ->
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.fromParts("package", activity.packageName, null)
                                            }
                                    }
                                    activity.startActivity(intent)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = colors.surface) {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = {
                                when (tab) {
                                    AppTab.DASHBOARD -> Icon(Icons.Rounded.Dashboard, contentDescription = stringResource(R.string.tab_dashboard))
                                    AppTab.STATISTICS -> Icon(Icons.Rounded.BarChart, contentDescription = stringResource(R.string.tab_statistics))
                                    AppTab.DEVICES -> Icon(Icons.Rounded.Devices, contentDescription = stringResource(R.string.tab_devices))
                                    AppTab.IMPORT -> Icon(Icons.Rounded.FileDownload, contentDescription = stringResource(R.string.tab_import))
                                    AppTab.SETTINGS -> Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.tab_settings))
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
                    dataEpoch = dataRevision,
                    onStartLive = { deviceIds ->
                        ContextCompat.startForegroundService(
                            activity,
                            LiveMonitorService.startIntent(activity, deviceIds.toLongArray()),
                        )
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
                            activity.setPermissionBanner(activity.getString(R.string.bluetooth_disabled))
                        } else if (!activity.ensureBluetoothScanPermissions()) {
                            // System permission dialog is open; do not claim the user denied yet.
                            activity.setPermissionBanner(null)
                        } else {
                            when (container.bluetoothGateway.startDiscovery()) {
                                null -> activity.setPermissionBanner(null)
                                "location_disabled" ->
                                    activity.setPermissionBanner(
                                        activity.getString(R.string.location_required_for_scan),
                                        BannerAction.LOCATION_SETTINGS,
                                    )
                                "location_precise_required" ->
                                    activity.setPermissionBanner(activity.getString(R.string.location_precise_required))
                                "missing_permission" ->
                                    activity.setPermissionBanner(activity.getString(R.string.permissions_denied))
                                "bluetooth_disabled" ->
                                    activity.setPermissionBanner(activity.getString(R.string.bluetooth_disabled))
                                else ->
                                    activity.setPermissionBanner(activity.getString(R.string.bluetooth_scan_failed))
                            }
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
                )
            }
        }
    }
}

