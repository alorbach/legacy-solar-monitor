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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
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
import com.alorbach.solarmonitor.data.model.PortfolioSummary
import com.alorbach.solarmonitor.data.model.TariffPeriodEntity
import com.alorbach.solarmonitor.data.settings.AppSettings
import com.alorbach.solarmonitor.device.BluetoothDeviceDescriptor
import com.alorbach.solarmonitor.domain.YieldFormatting
import com.alorbach.solarmonitor.i18n.LocaleController
import com.alorbach.solarmonitor.service.LiveMonitorService
import com.alorbach.solarmonitor.ui.ProductionChart
import com.alorbach.solarmonitor.ui.RemoteImportWizard
import com.alorbach.solarmonitor.ui.StatisticsScreen
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
    background = Color(0xFFF4F0E8),
    surface = Color(0xFFF8F5EE),
    surfaceVariant = Color(0xFFE4DED5),
    onBackground = Color(0xFF17212B),
    onSurface = Color(0xFF17212B),
    onSurfaceVariant = Color(0xFF5C636B),
)

private val SolarDarkColors = darkColorScheme(
    primary = Color(0xFFFFD86B),
    onPrimary = Color(0xFF17212B),
    secondary = Color(0xFFF4B400),
    background = Color(0xFF121820),
    surface = Color(0xFF1B2430),
    surfaceVariant = Color(0xFF2A3442),
    onBackground = Color(0xFFF4F0E8),
    onSurface = Color(0xFFF4F0E8),
    onSurfaceVariant = Color(0xFFB8BFC7),
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
    val portfolioRefreshKey = remember(devices, importJobs, localDataEpoch) {
        buildString {
            append(localDataEpoch)
            append('|')
            devices.forEach {
                append(it.id).append(':')
                    .append(it.lastLiveReadAtEpochSeconds ?: 0).append(':')
                    .append(it.lastArchiveSyncAtEpochSeconds ?: 0).append(':')
                    .append(it.lastConnectionStatus.orEmpty()).append('|')
            }
            importJobs.firstOrNull()?.let {
                append(it.id).append(':').append(it.status).append(':')
                    .append(it.completedAtEpochSeconds ?: it.createdAtEpochSeconds)
            }
        }
    }
    val portfolio by produceState(
        initialValue = PortfolioSummary(0, 0, 0, 0, 0, 0.0, null),
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    permissionMessage?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            message,
                            color = Color(0xFF8E2A2A),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.clickable {
                                val intent = when (bannerAction) {
                                    BannerAction.LOCATION_SETTINGS ->
                                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

                                    BannerAction.APP_SETTINGS ->
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", activity.packageName, null)
                                        }
                                }
                                activity.startActivity(intent)
                            },
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
                                    }
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
                                )
                                localDataEpoch += 1
                            }
                        },
                    ) {
                        Icon(Icons.Rounded.Power, contentDescription = stringResource(R.string.add_device))
                    }
                }
            },
        ) { padding ->
            when (currentTab) {
                AppTab.DASHBOARD -> DashboardTab(
                    modifier = Modifier.padding(padding),
                    portfolio = portfolio,
                    devices = devices,
                    container = container,
                    liveMessage = liveState.message,
                    liveActive = liveState.active,
                    dataEpoch = localDataEpoch,
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
                    modifier = Modifier.padding(padding),
                    devices = devices,
                    container = container,
                    settings = settings,
                    dataEpoch = localDataEpoch,
                )

                AppTab.DEVICES -> DevicesTab(
                    modifier = Modifier.padding(padding),
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
                )

                AppTab.IMPORT -> ImportTab(
                    modifier = Modifier.padding(padding),
                    devices = devices,
                    importJobs = importJobs,
                    container = container,
                    onDataChanged = { localDataEpoch += 1 },
                )

                AppTab.SETTINGS -> SettingsTab(
                    modifier = Modifier.padding(padding),
                    container = container,
                    settings = settings,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardTab(
    modifier: Modifier,
    portfolio: PortfolioSummary,
    devices: List<DeviceProfileEntity>,
    container: AppContainer,
    liveMessage: String,
    liveActive: Boolean,
    dataEpoch: Long,
    onStartLive: (List<Long>) -> Unit,
    onStopLive: () -> Unit,
) {
    var selectedDeviceId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(devices) {
        if (selectedDeviceId == null || devices.none { it.id == selectedDeviceId }) {
            selectedDeviceId = devices.firstOrNull()?.id
        }
    }
    var chartData by remember { mutableStateOf<List<DailyPoint>>(emptyList()) }
    LaunchedEffect(selectedDeviceId, dataEpoch) {
        chartData = selectedDeviceId?.let { container.repository.getDailyChart(it) } ?: emptyList()
    }
    val colors = MaterialTheme.colorScheme

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.portfolio), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile(Modifier.weight(1f), stringResource(R.string.metric_power), YieldFormatting.wattsLabel(portfolio.currentPowerW))
                        MetricTile(Modifier.weight(1f), stringResource(R.string.metric_today), YieldFormatting.whToKwhLabel(portfolio.todayYieldWh))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile(Modifier.weight(1f), stringResource(R.string.metric_month), YieldFormatting.whToKwhLabel(portfolio.monthYieldWh))
                        MetricTile(Modifier.weight(1f), stringResource(R.string.metric_year), YieldFormatting.whToKwhLabel(portfolio.yearYieldWh))
                    }
                    Text(
                        stringResource(
                            R.string.earnings_approx,
                            YieldFormatting.earningsLabel(portfolio.estimatedEarnings, portfolio.currency),
                        ),
                        color = colors.onSurfaceVariant,
                    )
                    StatusBadge(liveMessage)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            enabled = selectedDeviceId != null && !liveActive,
                            onClick = { selectedDeviceId?.let { onStartLive(listOf(it)) } },
                        ) {
                            Text(stringResource(R.string.start_live_monitor))
                        }
                        Button(
                            enabled = devices.isNotEmpty() && !liveActive,
                            onClick = { onStartLive(devices.map { it.id }) },
                        ) {
                            Text(stringResource(R.string.start_all_live_monitors))
                        }
                        if (liveActive) {
                            Button(onClick = onStopLive) {
                                Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.stop_live_monitor))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.stop_live_monitor))
                            }
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.production_chart), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.production_chart_subtitle),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ProductionChart(chartData)
                }
            }
        }
        if (devices.isEmpty()) {
            item {
                EmptyStateCard(
                    title = stringResource(R.string.no_devices_yet),
                    body = stringResource(R.string.no_devices_body),
                )
            }
        } else {
            items(devices, key = { it.id }) { device ->
                val summary by produceState(
                    initialValue = null as DeviceDashboardSummary?,
                    key1 = device.id,
                    key2 = device.lastLiveReadAtEpochSeconds,
                    key3 = "${device.lastConnectionStatus}:$dataEpoch",
                ) {
                    value = container.repository.getDeviceDashboard(device.id)
                }
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (selectedDeviceId == device.id) colors.surfaceVariant else colors.surface,
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .clickable { selectedDeviceId = device.id },
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(device.name, fontWeight = FontWeight.Bold)
                        Text(summary?.status ?: device.lastConnectionStatus ?: stringResource(R.string.idle), color = colors.onSurfaceVariant)
                        Text(
                            stringResource(
                                R.string.device_now_today,
                                YieldFormatting.wattsLabel(summary?.currentPowerW),
                                YieldFormatting.whToKwhLabel(summary?.todayYieldWh),
                            ),
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DevicesTab(
    modifier: Modifier,
    devices: List<DeviceProfileEntity>,
    container: AppContainer,
    bluetoothDevices: List<BluetoothDeviceDescriptor>,
    isScanning: Boolean,
    bluetoothEnabled: Boolean,
    onRefreshBluetooth: () -> Unit,
    onStopBluetooth: () -> Unit,
    onDataChanged: () -> Unit,
    assignedMacs: Set<String>,
    scrollToDeviceId: Long?,
    onRequestScrollToDevice: (Long) -> Unit,
    onScrollHandled: () -> Unit,
    monitoredDeviceIds: Set<Long> = emptySet(),
) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val rankedDevices = remember(bluetoothDevices) {
        bluetoothDevices.sortedWith(bluetoothDiscoveryUiComparator)
    }

    // The device list arrives from the database flow, so scroll once the new row exists.
    LaunchedEffect(scrollToDeviceId, devices) {
        val target = scrollToDeviceId ?: return@LaunchedEffect
        val position = devices.indexOfFirst { it.id == target }
        if (position >= 0) {
            listState.animateScrollToItem(position + DEVICE_LIST_HEADER_ITEMS)
            onScrollHandled()
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Bluetooth, contentDescription = stringResource(R.string.scan_bluetooth), tint = colors.onBackground)
                        Spacer(Modifier.width(10.dp))
                        Text("Bluetooth discovery", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Text("Scan nearby devices, then tap a result to create a device profile with that name and MAC.")
                    if (!bluetoothEnabled) {
                        Text(stringResource(R.string.bluetooth_disabled), color = Color(0xFF8E2A2A))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onRefreshBluetooth, enabled = bluetoothEnabled) {
                            Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.scan_bluetooth))
                            Spacer(Modifier.width(8.dp))
                            Text(if (isScanning) stringResource(R.string.scanning) else stringResource(R.string.scan_bluetooth))
                        }
                        Button(onClick = onStopBluetooth, enabled = isScanning) {
                            Text(stringResource(R.string.stop))
                        }
                    }
                    val nearbyCount = rankedDevices.count { !it.bonded }
                    Text("Nearby (unpaired): $nearbyCount · Paired: ${rankedDevices.size - nearbyCount}")
                    Text(
                        stringResource(R.string.bluetooth_scan_hint),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (rankedDevices.isEmpty()) {
                        Text(
                            if (isScanning) {
                                "Scanning the environment for Bluetooth devices..."
                            } else {
                                stringResource(R.string.bluetooth_no_results)
                            },
                            color = colors.onSurfaceVariant,
                        )
                    } else {
                        rankedDevices.forEach { candidate ->
                            val existingId = devices.firstOrNull {
                                it.btMac.equals(candidate.address, ignoreCase = true)
                            }?.id
                            BluetoothDiscoveryRow(
                                candidate = candidate,
                                alreadyAdded = existingId != null ||
                                    candidate.address.uppercase() in assignedMacs,
                                onClick = {
                                    if (existingId != null) {
                                        onRequestScrollToDevice(existingId)
                                    } else {
                                        scope.launch {
                                            val id = createDeviceFromBluetooth(
                                                container = container,
                                                seed = candidate,
                                                existingCount = devices.size,
                                            )
                                            onRequestScrollToDevice(id)
                                            onDataChanged()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        if (devices.isEmpty()) {
            item {
                Text(
                    "No devices yet. Tap a scan result above or use the + button to add one.",
                    color = colors.onSurfaceVariant,
                )
            }
        }
        items(devices, key = { it.id }) { device ->
            DeviceEditorCard(
                device = device,
                container = container,
                bluetoothDevices = rankedDevices,
                isScanning = isScanning,
                onRefreshBluetooth = onRefreshBluetooth,
                onDataChanged = onDataChanged,
                continuousLiveActive = device.id in monitoredDeviceIds,
            )
        }
    }
}

@Composable
private fun DeviceEditorCard(
    device: DeviceProfileEntity,
    container: AppContainer,
    bluetoothDevices: List<BluetoothDeviceDescriptor>,
    isScanning: Boolean,
    onRefreshBluetooth: () -> Unit,
    onDataChanged: () -> Unit,
    continuousLiveActive: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(device.id) { mutableStateOf(device.name) }
    var mac by rememberSaveable(device.id) { mutableStateOf(device.btMac ?: "") }
    var owner by rememberSaveable(device.id) { mutableStateOf(device.ownerName ?: "") }
    var smaPin by remember(device.id) { mutableStateOf(device.passwordRef ?: "0000") }
    var search by rememberSaveable(device.id) { mutableStateOf("") }
    var showPin by rememberSaveable(device.id) { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    var testRunning by remember { mutableStateOf(false) }
    var liveRunning by remember { mutableStateOf(false) }
    var syncRunning by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var clearHistoryRunning by remember { mutableStateOf(false) }
    var actionJob by remember { mutableStateOf<Job?>(null) }
    // The MAC field stays editable while an action runs, so Cancel must abort the MAC the action
    // was started with rather than whatever is in the text field now.
    var actionMac by remember { mutableStateOf<String?>(null) }
    val anyActionRunning = testRunning || liveRunning || syncRunning || clearHistoryRunning
    val colors = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val operationLabel = when {
        testRunning -> stringResource(R.string.testing_connection)
        liveRunning -> stringResource(R.string.reading_live_data)
        syncRunning -> stringResource(R.string.syncing_history)
        else -> null
    }

    val filteredDevices = remember(bluetoothDevices, search) {
        bluetoothDevices.filter {
            search.isBlank() ||
                (it.name ?: "").contains(search, ignoreCase = true) ||
                it.address.contains(search, ignoreCase = true)
        }
    }

    /** Returns null when the edited MAC belongs to another profile, so nothing was saved. */
    suspend fun persistEdits(): DeviceProfileEntity? {
        val updated = device.copy(
            name = name,
            btMac = mac,
            ownerName = owner,
            passwordRef = smaPin,
        )
        if (!container.repository.saveEditedDevice(updated)) {
            testSuccess = false
            testMessage = context.getString(R.string.duplicate_mac, mac)
            return null
        }
        actionMac = updated.btMac
        return updated
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceVariant),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { device.model ?: "Legacy SMA" }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Text(device.model ?: "Legacy SMA Bluetooth profile", color = colors.onSurfaceVariant)
                }
                AssistChip(
                    onClick = { },
                    enabled = false,
                    label = { Text(if (mac.isBlank()) "Unassigned" else mac) },
                )
            }
            OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Device name") }, singleLine = true)
            OutlinedTextField(value = owner, onValueChange = { owner = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Owner") }, singleLine = true)
            OutlinedTextField(
                value = smaPin,
                onValueChange = { smaPin = it.filter(Char::isDigit).take(12) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("SMA Bluetooth PIN / password") },
                singleLine = true,
                visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Text(
                        if (showPin) "Hide" else "Show",
                        modifier = Modifier
                            .clickable { showPin = !showPin }
                            .padding(8.dp),
                        color = colors.onSurfaceVariant,
                    )
                },
            )
            OutlinedTextField(value = mac, onValueChange = { mac = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Bluetooth MAC") }, singleLine = true)
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search nearby Bluetooth devices") },
                singleLine = true,
                trailingIcon = {
                    Icon(
                        imageVector = if (isScanning) Icons.Rounded.Refresh else Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.scan_bluetooth),
                        modifier = Modifier.clickable(onClick = onRefreshBluetooth),
                    )
                },
            )
            if (filteredDevices.isEmpty()) {
                Text(
                    if (isScanning) "Scanning the environment for Bluetooth devices..." else "No nearby results yet. Start a scan.",
                    color = colors.onSurfaceVariant,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(filteredDevices, key = { it.address }) { candidate ->
                        BluetoothCandidateChip(
                            candidate = candidate,
                            selected = mac.equals(candidate.address, ignoreCase = true),
                            onClick = {
                                mac = candidate.address
                                name = candidate.name?.takeIf { it.isNotBlank() } ?: name
                            },
                        )
                    }
                }
            }
            Text("Last live read: ${device.lastLiveReadAtEpochSeconds?.let(::formatEpochSeconds) ?: "--"}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("Last history sync: ${device.lastArchiveSyncAtEpochSeconds?.let(::formatEpochSeconds) ?: "--"}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("Socket strategy: ${device.lastSuccessfulSocketStrategy ?: "--"}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("Status: ${device.lastConnectionStatus ?: "--"}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            operationLabel?.let {
                Text(text = it, color = colors.onBackground, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(enabled = !anyActionRunning, onClick = { scope.launch { persistEdits() } }) {
                    Text(stringResource(R.string.save))
                }
                Button(
                    enabled = !anyActionRunning,
                    onClick = {
                        testRunning = true
                        actionJob = scope.launch {
                            try {
                                val saved = persistEdits() ?: return@launch
                                container.liveMonitoringRepository.testConnection(saved).also { result ->
                                    result.onSuccess {
                                        testSuccess = true
                                        testMessage = it
                                    }.onFailure {
                                        testSuccess = false
                                        testMessage = it.message ?: "Connection test failed"
                                    }
                                }
                            } catch (error: Throwable) {
                                if (error is CancellationException) {
                                    testSuccess = false
                                    testMessage = "Cancelled"
                                    throw error
                                }
                                testSuccess = false
                                testMessage = error.message ?: "Connection test failed"
                            } finally {
                                testRunning = false
                            }
                        }
                    },
                ) {
                    if (testRunning) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text(stringResource(R.string.test_connection))
                    }
                }
                Button(
                    enabled = !anyActionRunning,
                    onClick = {
                        liveRunning = true
                        actionJob = scope.launch {
                            try {
                                val saved = persistEdits() ?: return@launch
                                container.liveMonitoringRepository.start(saved.id).also { result ->
                                    result.onSuccess {
                                        testSuccess = true
                                        testMessage = it.status ?: "Live read OK"
                                        onDataChanged()
                                    }.onFailure {
                                        testSuccess = false
                                        testMessage = it.message ?: "Live read failed"
                                    }
                                }
                            } catch (error: Throwable) {
                                if (error is CancellationException) {
                                    testSuccess = false
                                    testMessage = "Cancelled"
                                    throw error
                                }
                                testSuccess = false
                                testMessage = error.message ?: "Live read failed"
                            } finally {
                                liveRunning = false
                            }
                        }
                    },
                ) {
                    if (liveRunning) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text(stringResource(R.string.live_read))
                    }
                }
                Button(
                    enabled = !anyActionRunning,
                    onClick = {
                        syncRunning = true
                        actionJob = scope.launch {
                            try {
                                val saved = persistEdits() ?: return@launch
                                container.liveMonitoringRepository.syncHistory(saved).also { result ->
                                    result.onSuccess {
                                        testSuccess = true
                                        testMessage = it
                                        onDataChanged()
                                    }.onFailure {
                                        testSuccess = false
                                        testMessage = it.message ?: "Archive sync failed"
                                    }
                                }
                            } catch (error: Throwable) {
                                if (error is CancellationException) {
                                    testSuccess = false
                                    testMessage = "Cancelled"
                                    throw error
                                }
                                testSuccess = false
                                testMessage = error.message ?: "Archive sync failed"
                            } finally {
                                syncRunning = false
                            }
                        }
                    },
                ) {
                    if (syncRunning) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text(stringResource(R.string.sync_history))
                    }
                }
                if (testRunning || liveRunning || syncRunning) {
                    Button(onClick = {
                        actionJob?.cancel()
                        actionJob = null
                        container.liveMonitoringRepository.cancelInFlight(actionMac)
                        // Keep *Running flags set until the coroutine finally-block clears them
                        // so overlapping Test/Live/Sync cannot start while RFCOMM is still winding down.
                        testSuccess = false
                        testMessage = "Cancelled"
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
                Button(
                    enabled = !anyActionRunning && !liveRunning && !continuousLiveActive,
                    onClick = { showClearHistoryConfirm = true },
                ) {
                    Text(stringResource(R.string.clear_history))
                }
                Button(enabled = !anyActionRunning, onClick = { scope.launch { container.repository.deleteDevice(device.id) } }) {
                    Text(stringResource(R.string.delete))
                }
            }
            if (showClearHistoryConfirm) {
                AlertDialog(
                    onDismissRequest = { if (!clearHistoryRunning) showClearHistoryConfirm = false },
                    title = { Text(stringResource(R.string.clear_history_title)) },
                    text = { Text(stringResource(R.string.clear_history_body)) },
                    confirmButton = {
                        TextButton(
                            enabled = !clearHistoryRunning,
                            onClick = {
                                clearHistoryRunning = true
                                scope.launch {
                                    try {
                                        container.repository.clearDeviceHistory(device.id)
                                        container.cloudBackupCoordinator.enqueue(BackupTrigger.Auto)
                                        testSuccess = true
                                        testMessage = context.getString(R.string.clear_history_done)
                                        onDataChanged()
                                    } catch (error: Throwable) {
                                        testSuccess = false
                                        testMessage = error.message ?: context.getString(R.string.clear_history_failed)
                                    } finally {
                                        clearHistoryRunning = false
                                        showClearHistoryConfirm = false
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.clear_history_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            enabled = !clearHistoryRunning,
                            onClick = { showClearHistoryConfirm = false },
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            }
            Text(
                stringResource(R.string.sync_merge_hint),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (!device.lastDiagnostics.isNullOrBlank()) {
                Text(
                    text = if (showDiagnostics) "Hide diagnostics" else "Show diagnostics",
                    color = Color(0xFF5B4BA7),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { showDiagnostics = !showDiagnostics },
                )
                if (showDiagnostics) {
                    Text(text = device.lastDiagnostics ?: "", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            testMessage?.let { message ->
                Text(
                    text = message,
                    color = if (testSuccess) Color(0xFF246B3D) else Color(0xFF8E2A2A),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun BluetoothDiscoveryRow(
    candidate: BluetoothDeviceDescriptor,
    alreadyAdded: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isSma = candidate.name?.contains("SMA", ignoreCase = true) == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSma) colors.primary.copy(alpha = 0.12f) else colors.surfaceVariant,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                candidate.name ?: "Unnamed Bluetooth device",
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(candidate.address)
                    append(" • ")
                    append(if (candidate.bonded) "Bonded" else "Nearby")
                    candidate.rssi?.let { append(" • RSSI $it") }
                },
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            stringResource(
                if (alreadyAdded) R.string.bluetooth_already_added else R.string.bluetooth_add_from_scan,
            ),
            color = if (alreadyAdded) colors.onSurfaceVariant else colors.onBackground,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun BluetoothCandidateChip(
    candidate: BluetoothDeviceDescriptor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .width(190.dp)
            .background(
                if (selected) colors.primary else colors.surface,
                RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            candidate.name ?: "Unnamed Bluetooth device",
            color = if (selected) colors.onPrimary else colors.onBackground,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            candidate.address,
            color = if (selected) colors.onPrimary.copy(alpha = 0.85f) else colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            buildString {
                append(if (candidate.bonded) "Bonded" else "Nearby")
                candidate.rssi?.let { append(" • RSSI $it") }
            },
            color = if (selected) colors.onPrimary.copy(alpha = 0.85f) else colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ImportTab(
    modifier: Modifier,
    devices: List<DeviceProfileEntity>,
    importJobs: List<ImportJobEntity>,
    container: AppContainer,
    onDataChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedDeviceId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(devices) {
        if (selectedDeviceId == null || devices.none { it.id == selectedDeviceId }) {
            selectedDeviceId = devices.firstOrNull()?.id
        }
    }
    var importUrl by rememberSaveable { mutableStateOf("") }
    var importRunning by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var showRemoteWizard by rememberSaveable { mutableStateOf(false) }
    var showClearImportJobsConfirm by remember { mutableStateOf(false) }
    var rerunJob by remember { mutableStateOf<ImportJobEntity?>(null) }
    var rerunUsername by remember { mutableStateOf("") }
    var rerunPassword by remember { mutableStateOf("") }
    var rerunPort by remember { mutableStateOf("") }
    var rerunUrl by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val remoteImportSucceededMessage = stringResource(R.string.remote_import_succeeded)
    val rerunSucceededMessage = stringResource(R.string.rerun_import_succeeded)

    fun startRerun(
        job: ImportJobEntity,
        usernameOverride: String? = null,
        passwordOverride: String? = null,
        portOverride: Int? = null,
        urlOverride: String? = null,
    ) {
        scope.launch {
            importRunning = true
            importMessage = null
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val request = container.importManager.replayRequest(
                        job = job,
                        usernameOverride = usernameOverride,
                        passwordOverride = passwordOverride,
                        portOverride = portOverride,
                        urlOverride = urlOverride,
                    )
                    container.importManager.run(
                        request = request,
                        overwriteCopyPath = job.preservedCopyPath,
                    ).getOrThrow()
                }
            }
            importRunning = false
            // Re-runs may persist partial data before failing; always refresh views.
            onDataChanged()
            importMessage = result.fold(
                onSuccess = { rerunSucceededMessage },
                onFailure = { it.message ?: "Import failed" },
            )
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val deviceId = selectedDeviceId ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importRunning = true
            importMessage = null
            val result = container.importManager.run(
                ImportRequest.FileRequest(
                    deviceId = deviceId,
                    uri = uri,
                    sourceLabel = uri.lastPathSegment ?: "Local file",
                )
            )
            importRunning = false
            importMessage = result.fold(
                onSuccess = {
                    onDataChanged()
                    "Import succeeded"
                },
                onFailure = { it.message ?: "Import failed" },
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Import sources", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Import SBFspot CSV, ZIP, or SQLite files from local storage or a URL.")
                    if (devices.isEmpty()) {
                        Text("Add a device first, then import data for it.", color = colors.onSurfaceVariant)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(devices, key = { it.id }) { device ->
                                DeviceChip(
                                    label = device.name,
                                    selected = selectedDeviceId == device.id,
                                    onClick = { selectedDeviceId = device.id },
                                )
                            }
                        }
                        Button(
                            enabled = selectedDeviceId != null && !importRunning,
                            onClick = {
                                filePicker.launch(
                                    arrayOf(
                                        "text/*",
                                        "application/zip",
                                        "application/octet-stream",
                                        "*/*",
                                    )
                                )
                            },
                        ) {
                            Icon(Icons.Rounded.FileDownload, contentDescription = stringResource(R.string.import_from_file))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.import_from_file))
                        }
                        Button(
                            enabled = !importRunning,
                            onClick = { showRemoteWizard = true },
                        ) {
                            Icon(Icons.Rounded.Folder, contentDescription = stringResource(R.string.import_from_remote))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.import_from_remote))
                        }
                        OutlinedTextField(
                            value = importUrl,
                            onValueChange = { importUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Import URL") },
                            singleLine = true,
                        )
                        Button(
                            enabled = selectedDeviceId != null && importUrl.isNotBlank() && !importRunning,
                            onClick = {
                                val deviceId = selectedDeviceId ?: return@Button
                                scope.launch {
                                    importRunning = true
                                    importMessage = null
                                    val result = container.importManager.run(
                                        ImportRequest.UrlRequest(
                                            deviceId = deviceId,
                                            url = importUrl.trim(),
                                            sourceLabel = "URL import",
                                        )
                                    )
                                    importRunning = false
                                    importMessage = result.fold(
                                        onSuccess = {
                                            onDataChanged()
                                            "URL import succeeded"
                                        },
                                        onFailure = { it.message ?: "URL import failed" },
                                    )
                                }
                            },
                        ) {
                            if (importRunning) {
                                CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.import_running))
                            } else {
                                Icon(Icons.Rounded.CloudUpload, contentDescription = stringResource(R.string.import_from_url))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.import_from_url))
                            }
                        }
                        importMessage?.let { Text(it, color = colors.onSurfaceVariant) }
                    }
                }
            }
        }
        if (importJobs.isEmpty()) {
            item {
                EmptyStateCard(
                    stringResource(R.string.no_imports_yet),
                    stringResource(R.string.no_imports_body),
                )
            }
        } else {
            item {
                OutlinedButton(
                    onClick = { showClearImportJobsConfirm = true },
                    enabled = !importRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.clear_import_jobs))
                }
            }
        }
        items(importJobs, key = { it.id }) { job ->
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceVariant),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            job.sourceLabel,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        if (job.canReplay() && devices.any { it.id == job.deviceId }) {
                            IconButton(
                                enabled = !importRunning,
                                onClick = {
                                    val config = job.replayConfig() ?: return@IconButton
                                    rerunJob = job
                                    rerunUsername = config.username.orEmpty()
                                    rerunPassword = ""
                                    rerunPort = config.port?.toString().orEmpty()
                                    rerunUrl = config.url.orEmpty()
                                },
                            ) {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    contentDescription = stringResource(R.string.rerun_import_job),
                                )
                            }
                        }
                        IconButton(
                            enabled = !importRunning,
                            onClick = {
                                scope.launch {
                                    container.repository.deleteImportJob(job.id)
                                    container.cloudBackupCoordinator.enqueue(BackupTrigger.Auto)
                                }
                            },
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.delete_import_job),
                            )
                        }
                    }
                    Text("${job.sourceType} • ${job.status}")
                    job.message?.let { Text(it) }
                    job.preservedCopyPath?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

        if (showClearImportJobsConfirm) {
            AlertDialog(
                onDismissRequest = { showClearImportJobsConfirm = false },
                title = { Text(stringResource(R.string.clear_import_jobs_title)) },
                text = { Text(stringResource(R.string.clear_import_jobs_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                container.repository.deleteAllImportJobs()
                                container.cloudBackupCoordinator.enqueue(BackupTrigger.Auto)
                                showClearImportJobsConfirm = false
                            }
                        },
                    ) {
                        Text(stringResource(R.string.clear_import_jobs_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearImportJobsConfirm = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        rerunJob?.let { job ->
            val config = job.replayConfig()
            val isUrlKind = config?.kind == "URL"
            val showCredentialFields = config?.kind != null && !isUrlKind
            // EncryptedSharedPreferences init/read is slow — never do it during composition.
            var hasStoredSecret by remember(job.id, job.passwordCredentialId) {
                mutableStateOf<Boolean?>(null)
            }
            LaunchedEffect(job.id, job.passwordCredentialId) {
                val credentialId = job.passwordCredentialId
                hasStoredSecret = if (credentialId.isNullOrBlank()) {
                    false
                } else {
                    withContext(Dispatchers.IO) {
                        container.credentialStore.getSecret(credentialId) != null
                    }
                }
            }
            val secretReady = hasStoredSecret != null
            val hasSecret = hasStoredSecret == true
            val showUrlField = isUrlKind && (config.url.isNullOrBlank() || !hasSecret)
            val canConfirmRerun = when {
                !secretReady -> false
                showUrlField && rerunUrl.isBlank() && !hasSecret -> false
                else -> true
            }
            AlertDialog(
                onDismissRequest = { if (!importRunning) rerunJob = null },
                title = { Text(stringResource(R.string.rerun_import_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            when {
                                showCredentialFields ->
                                    stringResource(R.string.rerun_import_credentials_body)
                                showUrlField ->
                                    stringResource(R.string.rerun_import_url_body)
                                else ->
                                    stringResource(R.string.rerun_import_body)
                            },
                        )
                        Text(job.sourceLabel, fontWeight = FontWeight.SemiBold)
                        if (showUrlField) {
                            OutlinedTextField(
                                value = rerunUrl,
                                onValueChange = { rerunUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text(
                                        if (hasSecret) {
                                            stringResource(R.string.rerun_import_url_optional)
                                        } else {
                                            stringResource(R.string.rerun_import_url)
                                        },
                                    )
                                },
                                singleLine = true,
                            )
                        }
                        if (showCredentialFields) {
                            OutlinedTextField(
                                value = rerunUsername,
                                onValueChange = { rerunUsername = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.rerun_import_username)) },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = rerunPassword,
                                onValueChange = { rerunPassword = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text(
                                        if (hasSecret) {
                                            stringResource(R.string.rerun_import_password_optional)
                                        } else {
                                            stringResource(R.string.rerun_import_password)
                                        },
                                    )
                                },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                            )
                            OutlinedTextField(
                                value = rerunPort,
                                onValueChange = { rerunPort = it.filter { ch -> ch.isDigit() }.take(5) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.rerun_import_port)) },
                                singleLine = true,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !importRunning && canConfirmRerun,
                        onClick = {
                            val target = job
                            rerunJob = null
                            when {
                                showCredentialFields ->
                                    startRerun(
                                        target,
                                        usernameOverride = rerunUsername.trim(),
                                        // Blank + stored secret → reuse; blank without → "".
                                        passwordOverride = if (hasSecret && rerunPassword.isEmpty()) {
                                            null
                                        } else {
                                            rerunPassword
                                        },
                                        portOverride = rerunPort.toIntOrNull()?.takeIf { it in 1..65535 },
                                    )
                                showUrlField ->
                                    startRerun(
                                        target,
                                        urlOverride = rerunUrl.trim().takeIf { it.isNotEmpty() },
                                    )
                                else -> startRerun(target)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.rerun_import_confirm))
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !importRunning,
                        onClick = { rerunJob = null },
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        if (showRemoteWizard) {
            RemoteImportWizard(
                devices = devices,
                importers = container.importers,
                importManager = container.importManager,
                initialDeviceId = selectedDeviceId,
                onDismiss = { showRemoteWizard = false },
                onImportSucceeded = {
                    onDataChanged()
                    importMessage = remoteImportSucceededMessage
                },
            )
        }
    }
}

@Composable
private fun SettingsTab(
    modifier: Modifier,
    container: AppContainer,
    settings: AppSettings,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var bucket by rememberSaveable { mutableStateOf(settings.gcsBucket) }
    var prefix by rememberSaveable { mutableStateOf(settings.gcsPrefix) }
    // The signed URL is a credential kept in encrypted storage; saved instance state is not
    // encrypted, so it is only held in memory and re-read from the store after process death.
    // Empty storage shows a path built from bucket/prefix; signature query is still required.
    var signedUrl by remember {
        mutableStateOf(
            CloudBackupPolicy.displaySignedUrlTemplate(
                settings.gcsSignedUrl,
                settings.gcsBucket,
                settings.gcsPrefix,
            ),
        )
    }
    var bucketDirty by rememberSaveable { mutableStateOf(false) }
    var prefixDirty by rememberSaveable { mutableStateOf(false) }
    var signedUrlDirty by remember { mutableStateOf(false) }
    var signedUrlPathLocked by remember { mutableStateOf(false) }
    var includeDatabase by rememberSaveable { mutableStateOf(settings.backupIncludeDatabase) }
    var includeImports by rememberSaveable { mutableStateOf(settings.backupIncludeImportCopies) }
    var includeDatabaseDirty by rememberSaveable { mutableStateOf(false) }
    var includeImportsDirty by rememberSaveable { mutableStateOf(false) }
    var showSignedUrl by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val neverLabel = stringResource(R.string.backup_never)
    val backupRunning by produceState(initialValue = false, context) {
        val liveData = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(CloudBackupPolicy.UNIQUE_WORK_NAME)
        val observer = Observer<List<WorkInfo>> { infos ->
            value = infos.any { it.state == WorkInfo.State.RUNNING }
        }
        liveData.observeForever(observer)
        awaitDispose { liveData.removeObserver(observer) }
    }

    fun refreshAutoPath(nextBucket: String = bucket, nextPrefix: String = prefix) {
        if (signedUrlPathLocked) return
        signedUrl = CloudBackupPolicy.withAutoPath(signedUrl, nextBucket, nextPrefix)
        signedUrlDirty = true
    }

    fun normalizeStoredSignedUrl(raw: String, nextBucket: String, nextPrefix: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed == CloudBackupPolicy.DEFAULT_SIGNED_URL_TEMPLATE) return ""
        if (trimmed == CloudBackupPolicy.buildPathTemplate(nextBucket, nextPrefix)) return ""
        if (trimmed == CloudBackupPolicy.buildDatabaseObjectUrl(nextBucket, nextPrefix)) return ""
        return trimmed
    }

    val signedUrlCoversOnlyDatabase = CloudBackupPolicy.selectableBackupFilenames(
        signedUrl,
        listOf(CloudBackupPolicy.DATABASE_BACKUP_FILENAME, "example.csv"),
    ) == listOf(CloudBackupPolicy.DATABASE_BACKUP_FILENAME)

    LaunchedEffect(
        settings.gcsBucket,
        settings.gcsPrefix,
        settings.gcsSignedUrl,
        settings.backupIncludeDatabase,
        settings.backupIncludeImportCopies,
    ) {
        if (!bucketDirty) bucket = settings.gcsBucket
        if (!prefixDirty) prefix = settings.gcsPrefix
        if (!signedUrlDirty) {
            signedUrl = CloudBackupPolicy.displaySignedUrlTemplate(
                settings.gcsSignedUrl,
                if (bucketDirty) bucket else settings.gcsBucket,
                if (prefixDirty) prefix else settings.gcsPrefix,
            )
            signedUrlPathLocked = false
        }
        if (!includeDatabaseDirty) includeDatabase = settings.backupIncludeDatabase
        if (!includeImportsDirty) includeImports = settings.backupIncludeImportCopies
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.language), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "" to stringResource(R.string.language_system),
                            "de" to stringResource(R.string.language_german),
                            "en" to stringResource(R.string.language_english),
                        ).forEach { (tag, label) ->
                            DeviceChip(
                                label = label,
                                selected = settings.languageTag == tag,
                                onClick = {
                                    scope.launch {
                                        container.settingsStore.update { it.copy(languageTag = tag) }
                                        LocaleController.apply(context, tag)
                                    }
                                },
                            )
                        }
                    }
                    Text(stringResource(R.string.language_restart_hint), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.tab_settings))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.cloud_backup), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    OutlinedTextField(
                        value = bucket,
                        onValueChange = {
                            bucket = it
                            bucketDirty = true
                            refreshAutoPath(nextBucket = it)
                        },
                        label = { Text(stringResource(R.string.gcs_bucket)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = prefix,
                        onValueChange = {
                            prefix = it
                            prefixDirty = true
                            refreshAutoPath(nextPrefix = it)
                        },
                        label = { Text(stringResource(R.string.gcs_prefix)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = signedUrl,
                        onValueChange = {
                            signedUrl = it
                            signedUrlDirty = true
                            signedUrlPathLocked = true
                        },
                        label = { Text(stringResource(R.string.signed_url_template)) },
                        supportingText = { Text(stringResource(R.string.signed_url_template_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2,
                        visualTransformation = if (showSignedUrl) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            Text(
                                if (showSignedUrl) stringResource(R.string.hide) else stringResource(R.string.show),
                                modifier = Modifier
                                    .clickable { showSignedUrl = !showSignedUrl }
                                    .padding(8.dp),
                                color = colors.onSurfaceVariant,
                            )
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.backup_include_database), modifier = Modifier.weight(1f))
                        Switch(
                            checked = includeDatabase,
                            onCheckedChange = {
                                includeDatabase = it
                                includeDatabaseDirty = true
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.backup_include_import_copies))
                            if (signedUrlCoversOnlyDatabase && CloudBackupPolicy.isUploadConfigured(signedUrl)) {
                                Text(
                                    stringResource(R.string.backup_import_copies_signed_url_hint),
                                    color = colors.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Switch(
                            checked = includeImports &&
                                !(signedUrlCoversOnlyDatabase && CloudBackupPolicy.isUploadConfigured(signedUrl)),
                            enabled = !(signedUrlCoversOnlyDatabase && CloudBackupPolicy.isUploadConfigured(signedUrl)),
                            onCheckedChange = {
                                includeImports = it
                                includeImportsDirty = true
                            },
                        )
                    }
                    Text(stringResource(R.string.backup_status), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (backupRunning) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.backup_running), color = colors.onSurfaceVariant)
                        }
                    }
                    Text(
                        stringResource(
                            R.string.backup_last_attempt,
                            settings.backupLastAttemptEpochSeconds?.let(::formatEpochSeconds) ?: neverLabel,
                        ),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(
                            R.string.backup_last_success,
                            settings.backupLastSuccessEpochSeconds?.let(::formatEpochSeconds) ?: neverLabel,
                        ),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (settings.backupLastMessage.isNotBlank()) {
                        Text(
                            settings.backupLastMessage,
                            color = when (settings.backupLastOk) {
                                true -> colors.primary
                                false -> colors.error
                                null -> colors.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else if (!settings.cloudBackupEnabled) {
                        Text(
                            stringResource(R.string.backup_not_configured),
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = {
                        scope.launch {
                            container.settingsStore.update { stored ->
                                val nextBucket = if (bucketDirty) bucket else stored.gcsBucket
                                val nextPrefix = if (prefixDirty) prefix else stored.gcsPrefix
                                val raw = if (signedUrlDirty) signedUrl.trim() else stored.gcsSignedUrl
                                val url = normalizeStoredSignedUrl(raw, nextBucket, nextPrefix)
                                stored.copy(
                                    cloudBackupEnabled = CloudBackupPolicy.isUploadConfigured(url),
                                    gcsBucket = nextBucket,
                                    gcsPrefix = nextPrefix,
                                    gcsSignedUrl = url,
                                    backupIncludeDatabase = if (includeDatabaseDirty) includeDatabase else stored.backupIncludeDatabase,
                                    backupIncludeImportCopies = if (includeImportsDirty) includeImports else stored.backupIncludeImportCopies,
                                )
                            }
                            bucketDirty = false
                            prefixDirty = false
                            signedUrlDirty = false
                            signedUrlPathLocked = false
                            includeDatabaseDirty = false
                            includeImportsDirty = false
                        }
                    }) {
                        Text(stringResource(R.string.save_backup_settings))
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                if (bucketDirty || prefixDirty || signedUrlDirty || includeDatabaseDirty || includeImportsDirty) {
                                    container.settingsStore.update { stored ->
                                        val nextBucket = if (bucketDirty) bucket else stored.gcsBucket
                                        val nextPrefix = if (prefixDirty) prefix else stored.gcsPrefix
                                        val raw = if (signedUrlDirty) signedUrl.trim() else stored.gcsSignedUrl
                                        val url = normalizeStoredSignedUrl(raw, nextBucket, nextPrefix)
                                        stored.copy(
                                            cloudBackupEnabled = CloudBackupPolicy.isUploadConfigured(url),
                                            gcsBucket = nextBucket,
                                            gcsPrefix = nextPrefix,
                                            gcsSignedUrl = url,
                                            backupIncludeDatabase = if (includeDatabaseDirty) includeDatabase else stored.backupIncludeDatabase,
                                            backupIncludeImportCopies = if (includeImportsDirty) includeImports else stored.backupIncludeImportCopies,
                                        )
                                    }
                                    bucketDirty = false
                                    prefixDirty = false
                                    signedUrlDirty = false
                                    signedUrlPathLocked = false
                                    includeDatabaseDirty = false
                                    includeImportsDirty = false
                                }
                                container.cloudBackupCoordinator.enqueue(BackupTrigger.Manual)
                            }
                        },
                        enabled = !backupRunning && (
                            settings.cloudBackupEnabled ||
                                CloudBackupPolicy.isUploadConfigured(signedUrl)
                            ),
                    ) {
                        Text(stringResource(R.string.backup_now))
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(modifier: Modifier = Modifier, label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .background(colors.surfaceVariant, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StatusBadge(message: String) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = message,
        modifier = Modifier
            .background(colors.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = colors.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun DeviceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = label,
        modifier = Modifier
            .background(if (selected) colors.primary else colors.surfaceVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        color = if (selected) colors.onPrimary else colors.onSurface,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun EmptyStateCard(title: String, body: String) {
    val colors = MaterialTheme.colorScheme
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(26.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(body, color = colors.onSurfaceVariant)
        }
    }
}

private fun formatEpochSeconds(value: Long): String {
    return Instant.ofEpochSecond(value)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .toString()
}

private val bluetoothDiscoveryUiComparator = compareByDescending<BluetoothDeviceDescriptor> {
    it.name?.contains("SMA", ignoreCase = true) == true
}.thenByDescending {
    !it.bonded
}.thenByDescending {
    !it.name.isNullOrBlank()
}.thenByDescending {
    it.rssi ?: Short.MIN_VALUE
}.thenBy {
    it.name ?: it.address
}

private fun preferredBluetoothSeed(
    devices: List<BluetoothDeviceDescriptor>,
    excludedMacs: Set<String> = emptySet(),
): BluetoothDeviceDescriptor? {
    return devices
        .filterNot { it.address.uppercase() in excludedMacs }
        .minWithOrNull(bluetoothDiscoveryUiComparator)
}

/** Items rendered above the device rows in [DevicesTab] (the Bluetooth discovery card). */
private const val DEVICE_LIST_HEADER_ITEMS = 1

private suspend fun createDeviceFromBluetooth(
    container: AppContainer,
    seed: BluetoothDeviceDescriptor?,
    existingCount: Int,
): Long {
    // A MAC may only be profiled once, even when taps race ahead of the device list flow.
    val upsert = container.repository.saveDeviceForMac(
        DeviceProfileEntity(
            name = seed?.name?.takeIf { it.isNotBlank() } ?: "SMA Device ${existingCount + 1}",
            serial = null,
            model = "Legacy SMA",
            transport = DeviceTransport.BLUETOOTH_LEGACY,
            btMac = seed?.address,
            passwordRef = "0000",
            plantName = "MeinePVAnlage",
            ownerName = "",
            address = "",
            latitude = null,
            longitude = null,
            timezone = ZoneId.systemDefault().id,
            locale = Locale.getDefault().toLanguageTag(),
        ),
    )
    // Seed tariffs only for a fresh profile; an existing one may already carry edited tariffs.
    if (upsert.created) {
        container.repository.saveTariffs(
            upsert.id,
            listOf(
                TariffPeriodEntity(
                    deviceId = upsert.id,
                    validFromEpochDay = LocalDate.now().minusYears(15).toEpochDay(),
                    validToEpochDay = null,
                    pricePerKwh = 0.28,
                    currency = "EUR",
                ),
            ),
        )
    }
    return upsert.id
}
