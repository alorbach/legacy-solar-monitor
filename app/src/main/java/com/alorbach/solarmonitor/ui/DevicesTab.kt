package com.alorbach.solarmonitor.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.alorbach.solarmonitor.BuildConfig
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.AppContainer
import com.alorbach.solarmonitor.data.cloud.BackupTrigger
import com.alorbach.solarmonitor.data.cloud.CloudBackupPolicy
import com.alorbach.solarmonitor.data.importing.CsvFormat
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
import com.alorbach.solarmonitor.service.LivePollScheduler
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

@Composable
fun DevicesTab(
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
    dataEpoch: Long,
    onDeleteDevice: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var expandedDeviceId by rememberSaveable { mutableStateOf<Long?>(null) }
    val rankedDevices = remember(bluetoothDevices) {
        bluetoothDevices.sortedWith(bluetoothDiscoveryUiComparator)
    }

    // The device list arrives from the database flow, so scroll once the new row exists.
    LaunchedEffect(scrollToDeviceId, devices) {
        val target = scrollToDeviceId ?: return@LaunchedEffect
        expandedDeviceId = target
        val position = devices.indexOfFirst { it.id == target }
        if (position >= 0) {
            val headerCount = 1 + rankedDevices.size
            listState.animateScrollToItem(headerCount + position)
            onScrollHandled()
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
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
                        Text(stringResource(R.string.bluetooth_discovery), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Text(stringResource(R.string.bluetooth_discovery_body))
                    if (!bluetoothEnabled) {
                        Text(stringResource(R.string.bluetooth_disabled), color = colors.error)
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
                    Text(stringResource(R.string.bluetooth_nearby_paired, nearbyCount, rankedDevices.size - nearbyCount))
                    Text(
                        stringResource(R.string.bluetooth_scan_hint),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (rankedDevices.isEmpty()) {
                        Text(
                            if (isScanning) {
                                stringResource(R.string.bluetooth_scanning_env)
                            } else {
                                stringResource(R.string.bluetooth_no_results)
                            },
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (rankedDevices.isNotEmpty()) {
            items(rankedDevices, key = { "bt-${it.address}" }) { candidate ->
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
                                    context = context,
                                )
                                onRequestScrollToDevice(id)
                                onDataChanged()
                            }
                        }
                    },
                )
            }
        }
        if (devices.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.no_devices_add_hint),
                    color = colors.onSurfaceVariant,
                )
            }
        }
        items(devices, key = { it.id }) { device ->
            DeviceEditorCard(
                device = device,
                container = container,
                expanded = expandedDeviceId == device.id,
                onToggleExpanded = {
                    expandedDeviceId = if (expandedDeviceId == device.id) null else device.id
                },
                onDataChanged = onDataChanged,
                continuousLiveActive = device.id in monitoredDeviceIds,
                dataEpoch = dataEpoch,
                onDeleteDevice = onDeleteDevice,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeviceEditorCard(
    device: DeviceProfileEntity,
    container: AppContainer,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onDataChanged: () -> Unit,
    continuousLiveActive: Boolean = false,
    dataEpoch: Long,
    onDeleteDevice: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(device.id) { mutableStateOf(device.name) }
    var mac by rememberSaveable(device.id) { mutableStateOf(device.btMac ?: "") }
    var owner by rememberSaveable(device.id) { mutableStateOf(device.ownerName ?: "") }
    var smaPin by remember(device.id) { mutableStateOf(container.repository.displayPin(device)) }
    var timezone by rememberSaveable(device.id) { mutableStateOf(device.timezone) }
    var serialText by rememberSaveable(device.id) { mutableStateOf(device.serial?.toString().orEmpty()) }
    var modelText by rememberSaveable(device.id) { mutableStateOf(device.model.orEmpty()) }
    var serialBaseline by remember(device.id) { mutableStateOf(device.serial?.toString().orEmpty()) }
    var modelBaseline by remember(device.id) { mutableStateOf(device.model.orEmpty()) }
    LaunchedEffect(device.serial, device.model) {
        val incomingSerial = device.serial?.toString().orEmpty()
        val incomingModel = device.model.orEmpty()
        if (incomingSerial.isNotEmpty() && serialText == serialBaseline) {
            serialText = incomingSerial
        }
        if (incomingSerial.isNotEmpty()) serialBaseline = incomingSerial
        if (incomingModel.isNotBlank() && modelText == modelBaseline) {
            modelText = incomingModel
        }
        if (incomingModel.isNotBlank()) modelBaseline = incomingModel
    }
    var decimalPoint by rememberSaveable(device.id) {
        mutableStateOf(CsvFormat.normalizeDecimalPoint(device.decimalPoint))
    }
    var delimiter by rememberSaveable(device.id) {
        mutableStateOf(CsvFormat.normalizeDelimiter(device.delimiter))
    }
    var dateFormat by rememberSaveable(device.id) { mutableStateOf(device.dateFormat) }
    var showAdvanced by rememberSaveable(device.id) { mutableStateOf(false) }
    var showPin by rememberSaveable(device.id) { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    var testRunning by remember { mutableStateOf(false) }
    var liveRunning by remember { mutableStateOf(false) }
    var syncRunning by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var clearHistoryRunning by remember { mutableStateOf(false) }
    var actionJob by remember { mutableStateOf<Job?>(null) }
    var actionMac by remember { mutableStateOf<String?>(null) }
    val anyActionRunning = testRunning || liveRunning || syncRunning || clearHistoryRunning
    val colors = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val cancelledLabel = stringResource(R.string.live_cancelled)
    val connectionFailedLabel = stringResource(R.string.connection_test_failed)
    val liveOkLabel = stringResource(R.string.live_read_ok)
    val liveFailedLabel = stringResource(R.string.live_read_failed)
    val archiveFailedLabel = stringResource(R.string.archive_sync_failed)
    val deviceSavedLabel = stringResource(R.string.device_saved)
    val expandDeviceLabel = stringResource(R.string.expand_device)
    val collapseDeviceLabel = stringResource(R.string.collapse_device)
    val operationLabel = when {
        testRunning -> stringResource(R.string.testing_connection)
        liveRunning -> stringResource(R.string.reading_live_data)
        syncRunning -> stringResource(R.string.syncing_history)
        else -> null
    }

    suspend fun persistEdits(): DeviceProfileEntity? {
        val updated = device.copy(
            name = name,
            btMac = mac,
            ownerName = owner,
            timezone = runCatching { ZoneId.of(timezone.ifBlank { device.timezone }) }
                .getOrElse {
                    testSuccess = false
                    testMessage = context.getString(R.string.device_timezone_invalid)
                    return null
                }
                .id,
            serial = if (serialText.isBlank()) null else serialText.trim().toLongOrNull() ?: device.serial,
            model = modelText.trim().ifBlank { null },
            decimalPoint = CsvFormat.normalizeDecimalPoint(decimalPoint.ifBlank { device.decimalPoint }),
            delimiter = CsvFormat.normalizeDelimiter(delimiter.ifBlank { device.delimiter }),
            dateFormat = dateFormat.trim().ifBlank { device.dateFormat },
        )
        if (!container.repository.saveEditedDevice(updated, smaPin)) {
            testSuccess = false
            testMessage = context.getString(R.string.duplicate_mac, mac)
            return null
        }
        if (updated.timezone != device.timezone) {
            LivePollScheduler.syncAfterSettingsChange(context)
        }
        val saved = container.repository.getDevice(device.id) ?: updated
        actionMac = saved.btMac
        return saved
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceVariant),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable(onClick = onToggleExpanded)
                    .semantics {
                        contentDescription = if (expanded) {
                            collapseDeviceLabel
                        } else {
                            expandDeviceLabel
                        }
                    },
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        name.ifBlank { device.model ?: stringResource(R.string.legacy_sma) },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(device.model ?: stringResource(R.string.legacy_sma_profile), color = colors.onSurfaceVariant)
                }
                AssistChip(
                    onClick = onToggleExpanded,
                    enabled = true,
                    label = { Text(if (mac.isBlank()) stringResource(R.string.unassigned) else mac) },
                )
            }
            if (!expanded) {
                if (continuousLiveActive) {
                    Text(
                        stringResource(R.string.live_monitor_active),
                        color = colors.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    stringResource(R.string.status_label, device.lastConnectionStatus ?: stringResource(R.string.idle)),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (expanded) {
            OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.device_name)) }, singleLine = true)
            OutlinedTextField(value = owner, onValueChange = { owner = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.owner)) }, singleLine = true)
            OutlinedTextField(
                value = smaPin,
                onValueChange = { smaPin = it.filter(Char::isDigit).take(12) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.sma_pin)) },
                supportingText = { Text(stringResource(R.string.sma_pin_default_hint)) },
                singleLine = true,
                visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPin = !showPin }) {
                        Icon(
                            imageVector = if (showPin) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = stringResource(if (showPin) R.string.hide else R.string.show),
                        )
                    }
                },
            )
            OutlinedTextField(value = mac, onValueChange = { mac = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.bluetooth_mac)) }, singleLine = true)
            OutlinedTextField(
                value = serialText,
                onValueChange = { serialText = it.filter(Char::isDigit).take(12) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.device_serial)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = modelText,
                onValueChange = { modelText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.device_model)) },
                singleLine = true,
            )
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(stringResource(R.string.device_advanced))
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = timezone,
                    onValueChange = { timezone = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.device_timezone)) },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        ZoneId.systemDefault().id,
                        "Europe/Berlin",
                        "Europe/Vienna",
                        "UTC",
                    ).distinct().forEach { zone ->
                        FilterChip(
                            selected = timezone == zone,
                            onClick = { timezone = zone },
                            label = { Text(zone) },
                        )
                    }
                }
                Text(stringResource(R.string.csv_decimal_point), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeviceChip(
                        label = stringResource(R.string.csv_option_comma),
                        selected = decimalPoint == "comma",
                        onClick = { decimalPoint = "comma" },
                    )
                    DeviceChip(
                        label = stringResource(R.string.csv_option_point),
                        selected = decimalPoint == "point",
                        onClick = { decimalPoint = "point" },
                    )
                }
                Text(stringResource(R.string.csv_delimiter), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeviceChip(
                        label = stringResource(R.string.csv_option_semicolon),
                        selected = delimiter == "semicolon",
                        onClick = { delimiter = "semicolon" },
                    )
                    DeviceChip(
                        label = stringResource(R.string.csv_option_comma),
                        selected = delimiter == "comma",
                        onClick = { delimiter = "comma" },
                    )
                }
                OutlinedTextField(
                    value = dateFormat,
                    onValueChange = { dateFormat = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.csv_date_format)) },
                    singleLine = true,
                )
            }
            val deviceZone = parseZoneId(timezone)
            Text(stringResource(R.string.last_live_read, device.lastLiveReadAtEpochSeconds?.let { formatEpochSeconds(it, deviceZone) } ?: "--"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.last_history_sync, device.lastArchiveSyncAtEpochSeconds?.let { formatEpochSeconds(it, deviceZone) } ?: "--"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.socket_strategy, device.lastSuccessfulSocketStrategy ?: "--"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.status_label, device.lastConnectionStatus ?: "--"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            operationLabel?.let {
                Text(text = it, color = colors.onBackground, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(stringResource(R.string.device_connection_actions), fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(enabled = !anyActionRunning, onClick = {
                    scope.launch {
                        if (persistEdits() != null) {
                            testSuccess = true
                            testMessage = deviceSavedLabel
                        }
                    }
                }) {
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
                                        testMessage = it.message ?: connectionFailedLabel
                                    }
                                }
                            } catch (error: Throwable) {
                                if (error is CancellationException) {
                                    testSuccess = false
                                    testMessage = cancelledLabel
                                    throw error
                                }
                                testSuccess = false
                                testMessage = error.message ?: connectionFailedLabel
                            } finally {
                                testRunning = false
                            }
                        }
                    },
                ) {
                    if (testRunning) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp, color = colors.onPrimary)
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
                                        testMessage = it.status ?: liveOkLabel
                                        onDataChanged()
                                    }.onFailure {
                                        testSuccess = false
                                        testMessage = it.message ?: liveFailedLabel
                                    }
                                }
                            } catch (error: Throwable) {
                                if (error is CancellationException) {
                                    testSuccess = false
                                    testMessage = cancelledLabel
                                    throw error
                                }
                                testSuccess = false
                                testMessage = error.message ?: liveFailedLabel
                            } finally {
                                liveRunning = false
                            }
                        }
                    },
                ) {
                    if (liveRunning) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp, color = colors.onPrimary)
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
                                        testMessage = it.message ?: archiveFailedLabel
                                    }
                                }
                            } catch (error: Throwable) {
                                if (error is CancellationException) {
                                    testSuccess = false
                                    testMessage = cancelledLabel
                                    throw error
                                }
                                testSuccess = false
                                testMessage = error.message ?: archiveFailedLabel
                            } finally {
                                syncRunning = false
                            }
                        }
                    },
                ) {
                    if (syncRunning) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp, color = colors.onPrimary)
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
                        testMessage = cancelledLabel
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
            Text(stringResource(R.string.device_danger_actions), fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    enabled = !anyActionRunning && !liveRunning && !continuousLiveActive,
                    onClick = { showClearHistoryConfirm = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.errorContainer,
                        contentColor = colors.onErrorContainer,
                    ),
                ) {
                    Text(stringResource(R.string.clear_history))
                }
                OutlinedButton(
                    enabled = !anyActionRunning,
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                ) {
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
            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text(stringResource(R.string.delete_device_title)) },
                    text = { Text(stringResource(R.string.delete_device_body)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDeleteConfirm = false
                                onDeleteDevice(device.id)
                            },
                        ) {
                            Text(stringResource(R.string.delete_device_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) {
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
            TariffSection(deviceId = device.id, container = container, onSaved = onDataChanged)
            DeviceEventsSection(
                deviceId = device.id,
                container = container,
                dataEpoch = dataEpoch + (device.lastLiveReadAtEpochSeconds ?: 0) +
                    (device.lastArchiveSyncAtEpochSeconds ?: 0),
                zoneId = parseZoneId(timezone),
                deviceName = device.name,
            )
            if (!device.lastDiagnostics.isNullOrBlank()) {
                Text(
                    text = if (showDiagnostics) stringResource(R.string.hide_diagnostics) else stringResource(R.string.show_diagnostics),
                    color = colors.primary,
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
                    color = if (testSuccess) colors.primary else colors.error,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            }
        }
    }
}

@Composable
internal fun BluetoothDiscoveryRow(
    candidate: BluetoothDeviceDescriptor,
    alreadyAdded: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isSma = candidate.name?.contains("SMA", ignoreCase = true) == true
    val proximity = if (candidate.bonded) stringResource(R.string.bonded) else stringResource(R.string.nearby)
    val rssiLabel = candidate.rssi?.let { stringResource(R.string.rssi_suffix, it.toInt()) }
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
                candidate.name ?: stringResource(R.string.unnamed_bluetooth),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(candidate.address)
                    append(" • ")
                    append(proximity)
                    rssiLabel?.let { append(" • ").append(it) }
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
internal val bluetoothDiscoveryUiComparator = compareByDescending<BluetoothDeviceDescriptor> {
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

internal fun preferredBluetoothSeed(
    devices: List<BluetoothDeviceDescriptor>,
    excludedMacs: Set<String> = emptySet(),
): BluetoothDeviceDescriptor? {
    return devices
        .filterNot { it.address.uppercase() in excludedMacs }
        .minWithOrNull(bluetoothDiscoveryUiComparator)
}

internal suspend fun createDeviceFromBluetooth(
    container: AppContainer,
    seed: BluetoothDeviceDescriptor?,
    existingCount: Int,
    context: Context,
): Long {
    // A MAC may only be profiled once, even when taps race ahead of the device list flow.
    val upsert = container.repository.saveDeviceForMac(
        DeviceProfileEntity(
            name = seed?.name?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.default_device_name, existingCount + 1),
            serial = null,
            model = context.getString(R.string.legacy_sma),
            transport = DeviceTransport.BLUETOOTH_LEGACY,
            btMac = seed?.address,
            passwordRef = "0000",
            plantName = context.getString(R.string.default_plant_name),
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

@Preview(showBackground = true)
@Composable
private fun BluetoothDiscoveryRowPreview() {
    MaterialTheme {
        BluetoothDiscoveryRow(
            candidate = BluetoothDeviceDescriptor(
                name = "SMA Sunny Boy",
                address = "AA:BB:CC:DD:EE:FF",
                bonded = false,
                rssi = -55,
            ),
            alreadyAdded = false,
            onClick = {},
        )
    }
}
