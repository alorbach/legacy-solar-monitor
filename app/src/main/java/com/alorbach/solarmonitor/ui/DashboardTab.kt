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
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
import com.alorbach.solarmonitor.work.ScheduledImportWorker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardTab(
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
    var chartData by remember { mutableStateOf<List<DailyPoint>?>(null) }
    val selectedDevice = devices.firstOrNull { it.id == selectedDeviceId }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exportSuccess by remember { mutableStateOf(false) }
    val exportFailedLabel = stringResource(R.string.export_failed)
    LaunchedEffect(
        selectedDeviceId,
        dataEpoch,
        selectedDevice?.lastLiveReadAtEpochSeconds,
        selectedDevice?.lastArchiveSyncAtEpochSeconds,
    ) {
        chartData = null
        chartData = selectedDeviceId?.let { container.repository.getDailyChart(it) } ?: emptyList()
    }
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

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
                    StatusBadge(liveMessage, active = liveActive)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            enabled = selectedDeviceId != null && !liveActive,
                            onClick = { selectedDeviceId?.let { onStartLive(listOf(it)) } },
                        ) {
                            Text(stringResource(R.string.start_live_selected))
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
                    Text(
                        selectedDevice?.let { stringResource(R.string.production_chart_for, it.name) }
                            ?: stringResource(R.string.production_chart),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.production_chart_subtitle),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (chartData == null) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.dashboard_loading), color = colors.onSurfaceVariant)
                        }
                    } else {
                        ProductionChart(chartData.orEmpty())
                    }
                    exportMessage?.let { message ->
                        Text(
                            message,
                            color = if (exportSuccess) colors.onSurfaceVariant else colors.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (selectedDeviceId != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val id = selectedDeviceId ?: return@OutlinedButton
                                    scope.launch {
                                        val summary = container.repository.getDeviceDashboard(id) ?: return@launch
                                        runCatching {
                                            container.reportExporter.share(
                                                container.reportExporter.exportCsv(summary),
                                                "text/csv",
                                            )
                                        }.onFailure {
                                            exportSuccess = false
                                            exportMessage = exportFailedLabel
                                        }.onSuccess {
                                            exportSuccess = true
                                            exportMessage = null
                                        }
                                    }
                                },
                            ) { Text(stringResource(R.string.export_csv)) }
                            OutlinedButton(
                                onClick = {
                                    val id = selectedDeviceId ?: return@OutlinedButton
                                    scope.launch {
                                        val summary = container.repository.getDeviceDashboard(id) ?: return@launch
                                        runCatching {
                                            container.reportExporter.share(
                                                container.reportExporter.exportPdf(summary),
                                                "application/pdf",
                                            )
                                        }.onFailure {
                                            exportSuccess = false
                                            exportMessage = exportFailedLabel
                                        }.onSuccess {
                                            exportSuccess = true
                                            exportMessage = null
                                        }
                                    }
                                },
                            ) { Text(stringResource(R.string.export_pdf)) }
                        }
                    }
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
                        Text(
                            device.name,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
