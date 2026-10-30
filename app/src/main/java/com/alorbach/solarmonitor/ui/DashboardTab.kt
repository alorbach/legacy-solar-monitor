package com.alorbach.solarmonitor.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.alorbach.solarmonitor.data.model.StatsPoint
import com.alorbach.solarmonitor.data.model.TariffPeriodEntity
import com.alorbach.solarmonitor.data.settings.AppSettings
import com.alorbach.solarmonitor.device.BluetoothDeviceDescriptor
import com.alorbach.solarmonitor.device.SmaStatusLabels
import com.alorbach.solarmonitor.domain.YieldFormatting
import com.alorbach.solarmonitor.i18n.LocaleController
import com.alorbach.solarmonitor.service.LiveMonitoringPauseReason
import com.alorbach.solarmonitor.service.LiveMonitoringStatusStore
import com.alorbach.solarmonitor.work.ScheduledImportWorker
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private data class DashboardChartLoad(
    val deviceId: Long? = null,
    val timezone: String? = null,
    val daily: List<DailyPoint>? = null,
    val hourly: List<StatsPoint>? = null,
    val currency: String? = null,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardTab(
    modifier: Modifier,
    portfolio: PortfolioSummary,
    devices: List<DeviceProfileEntity>,
    container: AppContainer,
    liveMessage: String,
    liveActive: Boolean,
    liveActiveDeviceIds: Set<Long>,
    dataEpoch: Long,
    onStartLive: (List<Long>) -> Unit,
    onStopLive: () -> Unit,
) {
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var selectedDeviceId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(devices) {
        if (selectedDeviceId == null || devices.none { it.id == selectedDeviceId }) {
            selectedDeviceId = devices.firstOrNull()?.id
        }
    }
    var selectedChartDay by remember { mutableStateOf<DailyPoint?>(null) }
    var selectedHourKey by remember { mutableStateOf<String?>(null) }
    var chartLoad by remember { mutableStateOf(DashboardChartLoad()) }
    val selectedDevice = devices.firstOrNull { it.id == selectedDeviceId }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exportSuccess by remember { mutableStateOf(false) }
    val exportFailedLabel = stringResource(R.string.export_failed)
    LaunchedEffect(
        selectedDeviceId,
        selectedDevice?.timezone,
        dataEpoch,
        selectedDevice?.lastLiveReadAtEpochSeconds,
        selectedDevice?.lastArchiveSyncAtEpochSeconds,
    ) {
        val deviceId = selectedDeviceId
        val timezone = selectedDevice?.timezone
        if (deviceId != chartLoad.deviceId || timezone != chartLoad.timezone) {
            chartLoad = DashboardChartLoad(deviceId = deviceId, timezone = timezone)
            selectedChartDay = null
            selectedHourKey = null
        }
        chartLoad = if (deviceId == null) {
            DashboardChartLoad(deviceId = null, timezone = timezone, daily = emptyList(), hourly = emptyList())
        } else {
            DashboardChartLoad(
                deviceId = deviceId,
                timezone = timezone,
                daily = container.repository.getDailyChart(deviceId),
                hourly = container.repository.getHourlySeriesToday(listOf(deviceId)),
                currency = container.repository.currencyForDevices(listOf(deviceId)),
            )
        }
    }
    val chartData = chartLoad.daily
    val hourlyToday = chartLoad.hourly
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val monitoringStatus by remember(context) { LiveMonitoringStatusStore.observe(context) }
        .collectAsStateWithLifecycle()

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
                    if (portfolio.currency == null) {
                        Text(stringResource(R.string.earnings_mixed_currencies), style = MaterialTheme.typography.bodyMedium)
                    }
                    StatusBadge(liveMessage, active = liveActive)
                    AdaptivePairRow() {
                        DashboardPrimaryMetric(Modifier.weight(1f), stringResource(R.string.metric_power), YieldFormatting.wattsLabel(portfolio.currentPowerW), liveActive)
                        DashboardPrimaryMetric(Modifier.weight(1f), stringResource(R.string.metric_today), YieldFormatting.whToKwhLabel(portfolio.todayYieldWh), false)
                    }
                    AdaptivePairRow() {
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
                    if (!liveActive && monitoringStatus.reason != LiveMonitoringPauseReason.STOPPED) {
                        val statusText = when (monitoringStatus.reason) {
                            LiveMonitoringPauseReason.RESUME_PENDING -> R.string.live_resume_pending
                            LiveMonitoringPauseReason.WAITING_FOR_WINDOW -> R.string.live_waiting_window
                            LiveMonitoringPauseReason.WAITING_FOR_WIFI -> R.string.live_waiting_wifi
                            LiveMonitoringPauseReason.NEEDS_RESUME -> R.string.live_needs_resume
                            LiveMonitoringPauseReason.NOTIFICATIONS_DISABLED -> R.string.live_notifications_disabled
                            LiveMonitoringPauseReason.BLUETOOTH_PERMISSION_REQUIRED -> R.string.live_bluetooth_permission_required
                            LiveMonitoringPauseReason.START_FAILED -> R.string.live_start_failed
                            LiveMonitoringPauseReason.STOPPED -> R.string.live_resume_pending
                        }
                        Text(stringResource(statusText), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        if (monitoringStatus.reason == LiveMonitoringPauseReason.WAITING_FOR_WINDOW ||
                            monitoringStatus.reason == LiveMonitoringPauseReason.WAITING_FOR_WIFI
                        ) {
                            monitoringStatus.nextAttemptAtMillis?.let {
                                Text(stringResource(R.string.live_next_attempt, formatEpochSeconds(it / 1000)), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (monitoringStatus.reason == LiveMonitoringPauseReason.NOTIFICATIONS_DISABLED) {
                            TextButton(onClick = {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                runCatching { context.startActivity(intent) }
                            }) { Text(stringResource(R.string.live_notification_settings)) }
                        }
                    }
                    monitoringStatus.lastSuccessfulPollAtMillis?.let {
                        Text(stringResource(R.string.live_last_success, formatEpochSeconds(it / 1000)), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    AdaptivePairRow() {
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = CompactButtonHeight),
                            contentPadding = CompactButtonContentPadding,
                            enabled = selectedDeviceId != null &&
                                selectedDeviceId !in liveActiveDeviceIds,
                            onClick = {
                                selectedDeviceId?.let { id ->
                                    onStartLive(listOf(id))
                                }
                            },
                        ) {
                            Text(
                                stringResource(
                                    if (liveActive) R.string.start_live_add_selected else R.string.start_live_selected,
                                ),
                            )
                        }
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = CompactButtonHeight),
                            contentPadding = CompactButtonContentPadding,
                            enabled = devices.isNotEmpty() &&
                                devices.any { it.id !in liveActiveDeviceIds },
                            onClick = { onStartLive(devices.map { it.id }) },
                        ) {
                            Text(
                                stringResource(R.string.start_all_live_monitors),
                            )
                        }
                    }
                    if (liveActive) {
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = CompactButtonHeight),
                            contentPadding = CompactButtonContentPadding,
                            onClick = onStopLive,
                        ) {
                            Icon(
                                Icons.Rounded.Stop,
                                contentDescription = stringResource(R.string.stop_live_monitoring),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.stop_live_monitoring),
                            )
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
                        selectedDevice?.let { stringResource(R.string.daily_stats_for, it.name) }
                            ?: stringResource(R.string.production_chart),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (devices.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(devices, key = { it.id }) { device ->
                                DeviceChip(
                                    label = device.name,
                                    selected = selectedDeviceId == device.id,
                                    onClick = { selectedDeviceId = device.id },
                                )
                            }
                        }
                    }
                    val loadedHours = hourlyToday.orEmpty()
                    if (loadedHours.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.hourly_today_subtitle,
                                YieldFormatting.whToKwhLabel(loadedHours.sumOf { it.yieldWh }),
                            ),
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        StatsBarChart(
                            points = loadedHours,
                            visibleBars = loadedHours.size.coerceAtLeast(1),
                            selectedBucketKey = selectedHourKey,
                            barAccent = settings.chartBarAccent,
                            height = 128.dp,
                            onBarClick = { key ->
                                selectedHourKey = if (selectedHourKey == key) null else key
                            },
                        )
                        loadedHours.firstOrNull { it.bucketKey == selectedHourKey }?.let { selected ->
                            Text(
                                stringResource(
                                    R.string.hourly_selected,
                                    selected.label,
                                    YieldFormatting.whToKwhLabel(selected.yieldWh),
                                    YieldFormatting.earningsLabel(selected.earnings, chartLoad.currency),
                                ),
                                color = colors.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.production_chart_subtitle),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val loadedChart = chartData
                    if (loadedChart == null) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.dashboard_loading), color = colors.onSurfaceVariant)
                        }
                    } else {
                        val periodTotal = loadedChart.sumOf { it.yieldWh }
                        val periodEarnings = loadedChart.sumOf { it.earnings }
                        val peak = loadedChart.maxByOrNull { it.yieldWh }
                        val avgWh = if (loadedChart.isEmpty()) 0L else periodTotal / loadedChart.size
                        val productionDays = loadedChart.count { it.yieldWh > 0L }
                        AdaptivePairRow() {
                            MetricTile(
                                Modifier.weight(1f),
                                stringResource(R.string.stats_total_yield),
                                YieldFormatting.whToKwhLabel(periodTotal),
                            )
                            MetricTile(
                                Modifier.weight(1f),
                                stringResource(R.string.stats_earnings_short),
                                YieldFormatting.earningsLabel(periodEarnings, chartLoad.currency),
                            )
                        }
                        AdaptivePairRow() {
                            MetricTile(
                                Modifier.weight(1f),
                                stringResource(R.string.daily_average),
                                YieldFormatting.whToKwhLabel(avgWh),
                            )
                            MetricTile(
                                Modifier.weight(1f),
                                stringResource(R.string.daily_peak),
                                buildString {
                                    append(YieldFormatting.whToKwhLabel(peak?.yieldWh))
                                    peak?.let {
                                        append(" · ")
                                        append(
                                            LocalDate.ofEpochDay(it.dateEpochDay).format(
                                                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM),
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                        Text(
                            stringResource(R.string.daily_production_days, productionDays, loadedChart.size),
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        ProductionChart(
                            points = loadedChart,
                            selectedEpochDay = selectedChartDay?.dateEpochDay,
                            onPointClick = { selectedChartDay = it },
                        )
                        selectedChartDay?.let { selected ->
                            val dateLabel = LocalDate.ofEpochDay(selected.dateEpochDay).format(
                                DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL),
                            )
                            Text(
                                stringResource(
                                    R.string.daily_selected,
                                    dateLabel,
                                    YieldFormatting.whToKwhLabel(selected.yieldWh),
                                    YieldFormatting.earningsLabel(selected.earnings, chartLoad.currency),
                                ),
                                color = colors.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    exportMessage?.let { message ->
                        Text(
                            message,
                            color = if (exportSuccess) colors.onSurfaceVariant else colors.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (selectedDeviceId != null) {
                        AdaptivePairRow() {
                            OutlinedButton(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = CompactButtonHeight),
                                contentPadding = CompactButtonContentPadding,
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
                            ) {
                                Text(
                                    stringResource(R.string.export_csv),
                                )
                            }
                            OutlinedButton(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = CompactButtonHeight),
                                contentPadding = CompactButtonContentPadding,
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
                            ) {
                                Text(
                                    stringResource(R.string.export_pdf),
                                )
                            }
                        }
                        AdaptivePairRow() {
                            OutlinedButton(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = CompactButtonHeight),
                                contentPadding = CompactButtonContentPadding,
                                enabled = loadedChart != null,
                                onClick = {
                                    val id = selectedDeviceId ?: return@OutlinedButton
                                    val series = loadedChart ?: return@OutlinedButton
                                    scope.launch {
                                        runCatching {
                                            container.reportExporter.share(
                                                container.reportExporter.exportSeriesCsv(
                                                    fileStem = "device-$id-daily",
                                                    points = com.alorbach.solarmonitor.domain.SeriesReportFormat.dailyPointsToStats(series),
                                                    includeEarnings = chartLoad.currency != null,
                                                ),
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
                            ) {
                                Text(
                                    stringResource(R.string.export_period_csv),
                                )
                            }
                            OutlinedButton(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = CompactButtonHeight),
                                contentPadding = CompactButtonContentPadding,
                                enabled = loadedChart != null,
                                onClick = {
                                    val id = selectedDeviceId ?: return@OutlinedButton
                                    val series = loadedChart ?: return@OutlinedButton
                                    scope.launch {
                                        runCatching {
                                            container.reportExporter.share(
                                                container.reportExporter.exportSeriesPdf(
                                                    fileStem = "device-$id-daily",
                                                    deviceLabel = selectedDevice?.name ?: "",
                                                    periodLabel = context.getString(R.string.production_chart_subtitle),
                                                    currency = chartLoad.currency,
                                                    points = com.alorbach.solarmonitor.domain.SeriesReportFormat.dailyPointsToStats(series),
                                                ),
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
                            ) {
                                Text(
                                    stringResource(R.string.export_period_pdf),
                                )
                            }
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                device.name,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            if (device.id in liveActiveDeviceIds) {
                                StatusBadge(stringResource(R.string.live_monitor_active), active = true)
                            }
                        }
                        Text(
                            SmaStatusLabels.displayStatus(
                                context,
                                summary?.status ?: device.lastConnectionStatus,
                            ) ?: stringResource(R.string.idle),
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            stringResource(
                                R.string.device_now_today,
                                YieldFormatting.wattsLabel(summary?.currentPowerW),
                                YieldFormatting.whToKwhLabel(summary?.todayYieldWh),
                            ),
                            color = colors.onSurfaceVariant,
                        )
                        LiveElectricalDetails(summary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardPrimaryMetric(modifier: Modifier, label: String, value: String, live: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .background(
                if (live) colors.tertiaryContainer else colors.secondary.copy(alpha = 0.16f),
                RoundedCornerShape(18.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(value, color = colors.onSurface, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LiveElectricalDetails(summary: DeviceDashboardSummary?) {
    if (summary == null) return
    val context = LocalContext.current
    val parts = mutableListOf<String>()
    val pdc1 = summary.pdc1
    if (pdc1 != null) parts += stringResource(R.string.live_dc1, YieldFormatting.wattsLabel(pdc1))
    val pdc2 = summary.pdc2
    if (pdc2 != null) parts += stringResource(R.string.live_dc2, YieldFormatting.wattsLabel(pdc2))
    val pac1 = summary.pac1
    if (pac1 != null) parts += stringResource(R.string.live_ac1, YieldFormatting.wattsLabel(pac1))
    val pac2 = summary.pac2
    if (pac2 != null) parts += stringResource(R.string.live_ac2, YieldFormatting.wattsLabel(pac2))
    val pac3 = summary.pac3
    if (pac3 != null) parts += stringResource(R.string.live_ac3, YieldFormatting.wattsLabel(pac3))
    summary.temperatureC?.let { parts += String.format(Locale.getDefault(), "%.1f °C", it) }
    summary.frequencyHz?.let { parts += String.format(Locale.getDefault(), "%.2f Hz", it) }
    SmaStatusLabels.displayRelay(context, summary.gridRelay)?.let { parts += it }
    val bt = summary.btSignalPercent
    if (bt != null) {
        parts += stringResource(
            R.string.live_bt_signal,
            String.format(Locale.getDefault(), "%.0f", bt),
        )
    }
    val serial = summary.serial
    if (serial != null) parts += stringResource(R.string.live_serial, serial.toString())
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}
