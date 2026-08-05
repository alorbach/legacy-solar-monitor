package com.alorbach.solarmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.alorbach.solarmonitor.data.AppContainer
import com.alorbach.solarmonitor.data.importing.ImportRequest
import com.alorbach.solarmonitor.data.model.DeviceProfileEntity
import com.alorbach.solarmonitor.data.model.DeviceTransport
import com.alorbach.solarmonitor.data.model.DailyPoint
import com.alorbach.solarmonitor.data.model.ImportJobEntity
import com.alorbach.solarmonitor.data.model.PortfolioSummary
import com.alorbach.solarmonitor.data.model.TariffPeriodEntity
import com.alorbach.solarmonitor.data.settings.AppSettings
import com.alorbach.solarmonitor.device.BluetoothDeviceDescriptor
import com.alorbach.solarmonitor.domain.YieldFormatting
import com.alorbach.solarmonitor.service.LiveMonitorService
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container: AppContainer by lazy {
        (application as SolarMonitorApplication).container
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissionsIfNeeded()
        setContent {
            MaterialTheme {
                SolarMonitorApp(container = container, activity = this)
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = buildList {
            add(Manifest.permission.INTERNET)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }
}

private enum class AppTab(val title: String, val icon: @Composable () -> Unit) {
    DASHBOARD("Dashboard", { Icon(Icons.Rounded.Dashboard, contentDescription = null) }),
    DEVICES("Devices", { Icon(Icons.Rounded.Devices, contentDescription = null) }),
    IMPORT("Import", { Icon(Icons.Rounded.FileDownload, contentDescription = null) }),
    SETTINGS("Settings", { Icon(Icons.Rounded.Settings, contentDescription = null) }),
}

@Composable
private fun SolarMonitorApp(container: AppContainer, activity: MainActivity) {
    val scope = rememberCoroutineScope()
    var currentTab by rememberSaveable { mutableStateOf(AppTab.DASHBOARD) }
    val devices by container.repository.observeDevices().collectAsState(initial = emptyList())
    val importJobs by container.repository.observeImportJobs().collectAsState(initial = emptyList())
    val liveState by container.liveMonitoringRepository.state.collectAsState()
    val bluetoothDevices by container.bluetoothGateway.discoveredDevices.collectAsState()
    val isScanning by container.bluetoothGateway.isDiscovering.collectAsState()
    val settings by container.settingsStore.settings.collectAsState(initial = AppSettings())
    val portfolio by produceState(
        initialValue = PortfolioSummary(0, 0, 0, 0, 0, 0.0, null),
        key1 = devices,
    ) {
        value = container.repository.getPortfolioSummary()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFF4F0E8),
    ) {
        Scaffold(
            containerColor = Color(0xFFF4F0E8),
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFFF4B400), Color(0xFFF0D78A), Color(0xFFF4F0E8))
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Text(
                        "SMA Solar Monitor",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF17212B),
                        maxLines = 1,
                    )
                    Text(
                        "Legacy Bluetooth solar monitoring, imports, reports, and widgets",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF3B434C),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFFF8F5EE)) {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = tab.icon,
                            label = { Text(tab.title) },
                        )
                    }
                }
            },
            floatingActionButton = {
                if (currentTab == AppTab.DEVICES) {
                    FloatingActionButton(
                        containerColor = Color(0xFF17212B),
                        contentColor = Color(0xFFFFD86B),
                        onClick = {
                            scope.launch {
                                val seed = bluetoothDevices.firstOrNull() ?: container.bluetoothGateway.listBondedDevices().firstOrNull()
                                val id = container.repository.saveDevice(
                                    DeviceProfileEntity(
                                        name = seed?.name ?: "SMA Device ${devices.size + 1}",
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
                                    )
                                )
                                container.repository.saveTariffs(
                                    id,
                                    listOf(
                                        TariffPeriodEntity(
                                            deviceId = id,
                                            validFromEpochDay = LocalDate.now().minusYears(15).toEpochDay(),
                                            validToEpochDay = null,
                                            pricePerKwh = 0.28,
                                            currency = "EUR",
                                        )
                                    )
                                )
                            }
                        },
                    ) {
                        Icon(Icons.Rounded.Power, contentDescription = "Add device")
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
                    onStartLive = { deviceId ->
                        ContextCompat.startForegroundService(
                            activity,
                            Intent(activity, LiveMonitorService::class.java)
                                .putExtra(LiveMonitorService.EXTRA_DEVICE_ID, deviceId),
                        )
                    },
                )

                AppTab.DEVICES -> DevicesTab(
                    modifier = Modifier.padding(padding),
                    devices = devices,
                    container = container,
                    bluetoothDevices = bluetoothDevices,
                    isScanning = isScanning,
                    onRefreshBluetooth = { container.bluetoothGateway.startDiscovery() },
                    onStopBluetooth = { container.bluetoothGateway.stopDiscovery() },
                )

                AppTab.IMPORT -> ImportTab(
                    modifier = Modifier.padding(padding),
                    devices = devices,
                    importJobs = importJobs,
                    onImportLegacyData = { deviceId ->
                        scope.launch {
                            val request = ImportRequest.UrlRequest(
                                deviceId = deviceId,
                                url = "https://example.invalid/legacy.zip",
                                sourceLabel = "Configured URL import",
                            )
                            container.importManager.run(request)
                        }
                    },
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

@Composable
private fun DashboardTab(
    modifier: Modifier,
    portfolio: PortfolioSummary,
    devices: List<DeviceProfileEntity>,
    container: AppContainer,
    liveMessage: String,
    onStartLive: (Long) -> Unit,
) {
    var selectedDeviceId by remember(devices) { mutableStateOf(devices.firstOrNull()?.id) }
    var chartData by remember { mutableStateOf<List<DailyPoint>>(emptyList()) }

    LaunchedEffect(selectedDeviceId) {
        chartData = selectedDeviceId?.let { container.repository.getDailyChart(it) } ?: emptyList()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            HeroCard(portfolio = portfolio, liveMessage = liveMessage)
        }
        item {
            if (devices.isEmpty()) {
                EmptyStateCard(
                    title = "No solar devices configured",
                    body = "Open Devices, scan your Bluetooth environment, and assign a Sunny Boy profile before starting live monitoring or imports.",
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(devices) { device ->
                        DeviceChip(
                            label = device.name,
                            selected = device.id == selectedDeviceId,
                            onClick = { selectedDeviceId = device.id },
                        )
                    }
                }
            }
        }
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFE4DED5)),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Daily production", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    ProductionChart(chartData)
                }
            }
        }
        items(devices) { device ->
            val summary by produceState<com.alorbach.solarmonitor.data.model.DeviceDashboardSummary?>(initialValue = null, key1 = device.id) {
                value = container.repository.getDeviceDashboard(device.id)
            }
            summary?.let {
                DeviceSummaryCard(summary = it, onStartLive = { onStartLive(device.id) })
            }
        }
    }
}

@Composable
private fun HeroCard(portfolio: PortfolioSummary, liveMessage: String) {
    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF17212B)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF17212B), Color(0xFF1B2E3A), Color(0xFF17212B))
                    )
                )
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Portfolio", color = Color(0xFFEDE7DB), style = MaterialTheme.typography.labelLarge)
                    Text(
                        YieldFormatting.wattsLabel(portfolio.currentPowerW),
                        color = Color(0xFFFFD86B),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                Spacer(Modifier.weight(1f))
                StatusBadge(liveMessage)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Today", YieldFormatting.whToKwhLabel(portfolio.todayYieldWh))
                MetricTile("Month", YieldFormatting.whToKwhLabel(portfolio.monthYieldWh))
                MetricTile("Year", YieldFormatting.whToKwhLabel(portfolio.yearYieldWh))
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String) {
    Box(
        modifier = Modifier
            .background(Color(0x22FFFFFF), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun MetricTile(label: String, value: String) {
    Column(
        modifier = Modifier
            .background(Color(0x26FFFFFF), RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(label, color = Color(0xFFEDE7DB), style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DeviceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) Color(0xFF17212B) else Color(0xFFE5DED2),
            labelColor = if (selected) Color(0xFFFFD86B) else Color(0xFF17212B),
        ),
    )
}

@Composable
private fun DeviceSummaryCard(
    summary: com.alorbach.solarmonitor.data.model.DeviceDashboardSummary,
    onStartLive: () -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF8F5EE)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(summary.deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(summary.model ?: "Legacy SMA profile", color = Color(0xFF5C636B))
                }
                Button(onClick = onStartLive) {
                    Text("Live")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("Current", YieldFormatting.wattsLabel(summary.currentPowerW))
                MiniStat("Today", YieldFormatting.whToKwhLabel(summary.todayYieldWh))
                MiniStat("Month", YieldFormatting.whToKwhLabel(summary.monthYieldWh))
            }
            Text("Year ${YieldFormatting.whToKwhLabel(summary.yearlyYieldWh)}")
            Text("Earnings ${"%.2f".format(summary.estimatedEarnings)} ${summary.currency ?: ""}")
            Text("Status ${summary.status ?: "--"}")
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column(
        modifier = Modifier
            .background(Color(0xFFEDE6D7), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF5C636B))
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DevicesTab(
    modifier: Modifier,
    devices: List<DeviceProfileEntity>,
    container: AppContainer,
    bluetoothDevices: List<BluetoothDeviceDescriptor>,
    isScanning: Boolean,
    onRefreshBluetooth: () -> Unit,
    onStopBluetooth: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF8F5EE)),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Bluetooth, contentDescription = null, tint = Color(0xFF17212B))
                        Spacer(Modifier.width(10.dp))
                        Text("Bluetooth discovery", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Text("Scan the nearby environment, then tap a result inside any device card to apply its name and MAC address.")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onRefreshBluetooth) {
                            Icon(Icons.Rounded.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isScanning) "Scanning..." else "Scan")
                        }
                        Button(onClick = onStopBluetooth, enabled = isScanning) {
                            Text("Stop")
                        }
                    }
                    Text("Results visible: ${bluetoothDevices.size}")
                }
            }
        }
        items(devices) { device ->
            DeviceEditorCard(
                device = device,
                container = container,
                bluetoothDevices = bluetoothDevices,
                isScanning = isScanning,
                onRefreshBluetooth = onRefreshBluetooth,
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
) {
    val scope = rememberCoroutineScope()
    var name by remember(device) { mutableStateOf(device.name) }
    var mac by remember(device) { mutableStateOf(device.btMac ?: "") }
    var owner by remember(device) { mutableStateOf(device.ownerName ?: "") }
    var smaPin by remember(device) { mutableStateOf(device.passwordRef ?: "0000") }
    var search by remember(device.id) { mutableStateOf("") }
    var showPin by remember(device.id) { mutableStateOf(false) }
    var testMessage by remember(device.id) { mutableStateOf<String?>(null) }
    var testSuccess by remember(device.id) { mutableStateOf(false) }
    var testRunning by remember(device.id) { mutableStateOf(false) }
    var liveRunning by remember(device.id) { mutableStateOf(false) }
    var syncRunning by remember(device.id) { mutableStateOf(false) }
    var showDiagnostics by remember(device.id) { mutableStateOf(false) }
    val anyActionRunning = testRunning || liveRunning || syncRunning
    val operationLabel = when {
        testRunning -> "Testing connection..."
        liveRunning -> "Reading live data..."
        syncRunning -> "Syncing history..."
        else -> null
    }

    val filteredDevices = remember(bluetoothDevices, search) {
        bluetoothDevices.filter {
            search.isBlank() ||
                (it.name ?: "").contains(search, ignoreCase = true) ||
                it.address.contains(search, ignoreCase = true)
        }
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFE4DED5)),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { device.model ?: "Legacy SMA" }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Text(device.model ?: "Legacy SMA Bluetooth profile", color = Color(0xFF5C636B))
                }
                AssistChip(
                    onClick = { },
                    enabled = false,
                    label = { Text(if (mac.isBlank()) "Unassigned" else mac) },
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Device name") },
                singleLine = true,
            )
            OutlinedTextField(
                value = owner,
                onValueChange = { owner = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Owner") },
                singleLine = true,
            )
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
                        modifier = Modifier.clickable { showPin = !showPin },
                        color = Color(0xFF5C636B),
                    )
                },
            )
            OutlinedTextField(
                value = mac,
                onValueChange = { mac = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bluetooth MAC") },
                singleLine = true,
            )
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search nearby Bluetooth devices") },
                singleLine = true,
                trailingIcon = {
                    Icon(
                        imageVector = if (isScanning) Icons.Rounded.Refresh else Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.clickable(onClick = onRefreshBluetooth),
                    )
                },
            )
            if (filteredDevices.isEmpty()) {
                Text(
                    if (isScanning) "Scanning the environment for Bluetooth devices..." else "No nearby results yet. Start a scan.",
                    color = Color(0xFF5C636B),
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(filteredDevices.take(8), key = { it.address }) { candidate ->
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
            Text(
                "Last live read: ${device.lastLiveReadAtEpochSeconds?.let(::formatEpochSeconds) ?: "--"}",
                color = Color(0xFF5C636B),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Last history sync: ${device.lastArchiveSyncAtEpochSeconds?.let(::formatEpochSeconds) ?: "--"}",
                color = Color(0xFF5C636B),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Socket strategy: ${device.lastSuccessfulSocketStrategy ?: "--"}",
                color = Color(0xFF5C636B),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Compatibility: ${device.legacyCompatibilityMode.name.replace('_', ' ')}",
                color = Color(0xFF5C636B),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Status: ${device.lastConnectionStatus ?: "--"}",
                color = Color(0xFF5C636B),
                style = MaterialTheme.typography.bodySmall,
            )
            operationLabel?.let {
                Text(
                    text = it,
                    color = Color(0xFF17212B),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = !anyActionRunning, onClick = {
                    scope.launch {
                        container.repository.saveDevice(
                            device.copy(
                                name = name,
                                btMac = mac,
                                ownerName = owner,
                                passwordRef = smaPin,
                            )
                        )
                    }
                }) {
                    Text("Save")
                }
                Button(
                    enabled = !anyActionRunning,
                    onClick = {
                        testRunning = true
                        container.liveMonitoringRepository.testConnection(device.id) { result ->
                            testRunning = false
                            result.onSuccess { message ->
                                testSuccess = true
                                testMessage = message
                            }.onFailure {
                                testSuccess = false
                                testMessage = it.message ?: "Connection test failed"
                            }
                        }
                    },
                ) {
                    if (testRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Text("Test")
                    }
                }
                Button(
                    enabled = !anyActionRunning,
                    onClick = {
                        liveRunning = true
                        container.liveMonitoringRepository.start(device.id) { result ->
                            liveRunning = false
                            result.onSuccess { snapshot ->
                                testSuccess = true
                                testMessage = snapshot.status ?: "Live read OK"
                            }.onFailure {
                                testSuccess = false
                                testMessage = it.message ?: "Live read failed"
                            }
                        }
                    },
                ) {
                    if (liveRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Text("Live")
                    }
                }
                Button(
                    enabled = !anyActionRunning,
                    onClick = {
                        syncRunning = true
                        container.liveMonitoringRepository.syncHistory(device.id) { result ->
                            syncRunning = false
                            result.onSuccess { message ->
                                testSuccess = true
                                testMessage = message
                            }.onFailure {
                                testSuccess = false
                                testMessage = it.message ?: "Archive sync failed"
                            }
                        }
                    },
                ) {
                    if (syncRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Text("Sync")
                    }
                }
                Button(enabled = !anyActionRunning, onClick = { scope.launch { container.repository.deleteDevice(device.id) } }) {
                    Text("Delete")
                }
            }
            if (!device.lastDiagnostics.isNullOrBlank()) {
                Text(
                    text = if (showDiagnostics) "Hide diagnostics" else "Show diagnostics",
                    color = Color(0xFF5B4BA7),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { showDiagnostics = !showDiagnostics },
                )
                if (showDiagnostics) {
                    Text(
                        text = device.lastDiagnostics ?: "",
                        color = Color(0xFF5C636B),
                        style = MaterialTheme.typography.bodySmall,
                    )
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
private fun BluetoothCandidateChip(
    candidate: BluetoothDeviceDescriptor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(190.dp)
            .background(
                if (selected) Color(0xFF17212B) else Color(0xFFF8F5EE),
                RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            candidate.name ?: "Unnamed Bluetooth device",
            color = if (selected) Color(0xFFFFD86B) else Color(0xFF17212B),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            candidate.address,
            color = if (selected) Color(0xFFEDE7DB) else Color(0xFF5C636B),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            buildString {
                append(if (candidate.bonded) "Bonded" else "Nearby")
                candidate.rssi?.let { append(" • RSSI $it") }
            },
            color = if (selected) Color(0xFFEDE7DB) else Color(0xFF5C636B),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ImportTab(
    modifier: Modifier,
    devices: List<DeviceProfileEntity>,
    importJobs: List<ImportJobEntity>,
    onImportLegacyData: (Long) -> Unit,
) {
    var selectedDeviceId by remember(devices) { mutableStateOf(devices.firstOrNull()?.id) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF8F5EE)),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Import sources", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Use URL, FTP, SFTP, ZIP, CSV, or legacy SBFspot.db imports to seed the local database.")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(devices) { device ->
                            DeviceChip(
                                label = device.name,
                                selected = selectedDeviceId == device.id,
                                onClick = { selectedDeviceId = device.id },
                            )
                        }
                    }
                    Button(
                        enabled = selectedDeviceId != null,
                        onClick = { selectedDeviceId?.let(onImportLegacyData) },
                    ) {
                        Icon(Icons.Rounded.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Run configured remote import")
                    }
                }
            }
        }
        items(importJobs) { job ->
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFE4DED5)),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(job.sourceLabel, fontWeight = FontWeight.Bold)
                    Text("${job.sourceType} • ${job.status}")
                    job.message?.let { Text(it) }
                    job.preservedCopyPath?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
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
    var bucket by remember(settings.gcsBucket) { mutableStateOf(settings.gcsBucket) }
    var prefix by remember(settings.gcsPrefix) { mutableStateOf(settings.gcsPrefix) }
    var signedUrl by remember(settings.gcsSignedUrl) { mutableStateOf(settings.gcsSignedUrl) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF8F5EE)),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Settings, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Cloud backup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    OutlinedTextField(value = bucket, onValueChange = { bucket = it }, label = { Text("GCS bucket") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = prefix, onValueChange = { prefix = it }, label = { Text("GCS prefix") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = signedUrl, onValueChange = { signedUrl = it }, label = { Text("Signed URL template") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        scope.launch {
                            container.settingsStore.update {
                                it.copy(
                                    cloudBackupEnabled = signedUrl.isNotBlank(),
                                    gcsBucket = bucket,
                                    gcsPrefix = prefix,
                                    gcsSignedUrl = signedUrl,
                                )
                            }
                        }
                    }) {
                        Text("Save backup settings")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(title: String, body: String) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF8F5EE)),
        shape = RoundedCornerShape(26.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(body, color = Color(0xFF5C636B))
        }
    }
}

@Composable
private fun ProductionChart(points: List<DailyPoint>) {
    val maxYield = (points.maxOfOrNull { it.yieldWh } ?: 1L).toFloat()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .background(Color(0xFFF9F6EF), RoundedCornerShape(22.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (points.isEmpty()) {
            Text("Import or sync data to render charts.")
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val step = size.width / (points.size - 1).coerceAtLeast(1)
                val offsets = points.mapIndexed { index, point ->
                    Offset(
                        x = index * step,
                        y = size.height - ((point.yieldWh / maxYield) * size.height),
                    )
                }
                offsets.zipWithNext().forEach { (start, end) ->
                    drawLine(
                        brush = Brush.linearGradient(listOf(Color(0xFFF4B400), Color(0xFF17212B))),
                        start = start,
                        end = end,
                        strokeWidth = 10f,
                        cap = StrokeCap.Round,
                    )
                }
                offsets.forEach { offset ->
                    drawCircle(Color(0xFFF4B400), radius = 7f, center = offset)
                }
            }
        }
    }
}

private fun formatEpochSeconds(value: Long): String {
    return Instant.ofEpochSecond(value)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .toString()
}
