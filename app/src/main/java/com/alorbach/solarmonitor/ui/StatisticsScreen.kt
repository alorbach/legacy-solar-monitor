package com.alorbach.solarmonitor.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.AppContainer
import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import com.alorbach.solarmonitor.data.model.DeviceProfileEntity
import com.alorbach.solarmonitor.data.model.StatsGranularity
import com.alorbach.solarmonitor.data.model.StatsPoint
import com.alorbach.solarmonitor.data.settings.AppSettings
import com.alorbach.solarmonitor.domain.YieldFormatting
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun StatisticsScreen(
    modifier: Modifier,
    devices: List<DeviceProfileEntity>,
    container: AppContainer,
    settings: AppSettings,
    dataEpoch: Long,
) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var granularity by remember(settings.statsGranularity) { mutableStateOf(settings.statsGranularity) }
    var selectedDeviceId by remember(settings.statsSelectedDeviceId) {
        mutableStateOf(settings.statsSelectedDeviceId)
    }
    val zoneId = remember(devices, selectedDeviceId) {
        val tz = devices.firstOrNull { it.id == selectedDeviceId }?.timezone
            ?: devices.firstOrNull()?.timezone
            ?: ZoneId.systemDefault().id
        runCatching { ZoneId.of(tz) }.getOrDefault(ZoneId.systemDefault())
    }
    var anchorDate by remember { mutableStateOf(LocalDate.now(zoneId)) }
    var points by remember { mutableStateOf<List<StatsPoint>>(emptyList()) }
    var periodEvents by remember { mutableStateOf<List<DeviceEventEntity>>(emptyList()) }
    var selectedBucketKey by remember { mutableStateOf<String?>(null) }
    var selectedBucketEvents by remember { mutableStateOf<List<DeviceEventEntity>?>(null) }
    var eventFilter by remember { mutableStateOf(EventFilter.ALL) }
    var currency by remember { mutableStateOf("EUR") }

    LaunchedEffect(devices) {
        if (selectedDeviceId != null && devices.none { it.id == selectedDeviceId }) {
            selectedDeviceId = null
        }
    }

    fun persistView() {
        scope.launch {
            container.settingsStore.update {
                it.copy(
                    statsGranularity = granularity,
                    statsSelectedDeviceId = selectedDeviceId,
                )
            }
        }
    }

    val deviceIdsKey = devices.map { it.id }.joinToString(separator = ",")
    // Hour/day charts refresh when live/archive samples land; year/month stay keyed by
    // ids + dataEpoch to avoid reloading full-history yearly series on every live tick.
    val liveRevision = if (
        granularity == StatsGranularity.HOUR || granularity == StatsGranularity.DAY
    ) {
        devices
            .filter { selectedDeviceId == null || it.id == selectedDeviceId }
            .joinToString(separator = ",") {
                "${it.id}:${it.lastLiveReadAtEpochSeconds}:${it.lastArchiveSyncAtEpochSeconds}:${it.timezone}"
            }
    } else {
        ""
    }
    LaunchedEffect(granularity, selectedDeviceId, anchorDate, dataEpoch, deviceIdsKey, liveRevision, zoneId) {
        val ids = if (selectedDeviceId == null) {
            devices.map { it.id }
        } else {
            listOfNotNull(selectedDeviceId)
        }
        points = when (granularity) {
            StatsGranularity.HOUR -> container.repository.getHourlySeries(ids, anchorDate)
            StatsGranularity.DAY -> container.repository.getDailySeries(ids, YearMonth.from(anchorDate))
            StatsGranularity.MONTH -> container.repository.getMonthlySeries(ids, anchorDate.year)
            StatsGranularity.YEAR -> container.repository.getYearlySeries(ids)
        }
        currency = container.repository.currencyForDevices(ids) ?: "EUR"
        periodEvents = container.repository.getEventsForLocalWindows(ids) { zone ->
            eventWindow(granularity, anchorDate, zone)
        }
        selectedBucketKey = null
        selectedBucketEvents = null
    }

    LaunchedEffect(selectedBucketKey, granularity, selectedDeviceId, anchorDate, deviceIdsKey) {
        val key = selectedBucketKey
        if (key == null) {
            selectedBucketEvents = null
            return@LaunchedEffect
        }
        val ids = if (selectedDeviceId == null) {
            devices.map { it.id }
        } else {
            listOfNotNull(selectedDeviceId)
        }
        selectedBucketEvents = container.repository.getEventsForLocalWindows(ids) { zone ->
            bucketWindow(granularity, key, anchorDate, zone) ?: (0L to -1L)
        }
    }

    val periodLabel = when (granularity) {
        StatsGranularity.HOUR ->
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
                .format(anchorDate)
        StatsGranularity.DAY ->
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(YearMonth.from(anchorDate))
        StatsGranularity.MONTH -> Year.from(anchorDate).toString()
        StatsGranularity.YEAR -> stringResource(R.string.stats_all_years)
    }

    val totalYield = points.sumOf { it.yieldWh }
    val peakPower = points.mapNotNull { it.peakPowerW }.maxOrNull()
    val totalEarnings = points.sumOf { it.earnings }

    val today = remember(zoneId) { LocalDate.now(zoneId) }
    val canGoPrevious = granularity != StatsGranularity.YEAR
    val canGoNext = when (granularity) {
        StatsGranularity.HOUR -> anchorDate.isBefore(today)
        StatsGranularity.DAY -> YearMonth.from(anchorDate).isBefore(YearMonth.from(today))
        StatsGranularity.MONTH -> anchorDate.year < today.year
        StatsGranularity.YEAR -> false
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
                    Text(
                        stringResource(R.string.tab_statistics),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(stringResource(R.string.stats_granularity), color = colors.onSurfaceVariant)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatsGranularity.entries.forEach { option ->
                            SelectChip(
                                label = when (option) {
                                    StatsGranularity.HOUR -> stringResource(R.string.stats_hour)
                                    StatsGranularity.DAY -> stringResource(R.string.stats_day)
                                    StatsGranularity.MONTH -> stringResource(R.string.stats_month)
                                    StatsGranularity.YEAR -> stringResource(R.string.stats_year)
                                },
                                selected = granularity == option,
                                onClick = {
                                    granularity = option
                                    persistView()
                                },
                            )
                        }
                    }
                    Text(stringResource(R.string.stats_inverter), color = colors.onSurfaceVariant)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SelectChip(
                            label = stringResource(R.string.stats_all_inverters),
                            selected = selectedDeviceId == null,
                            onClick = {
                                selectedDeviceId = null
                                persistView()
                            },
                        )
                        devices.forEach { device ->
                            SelectChip(
                                label = device.name,
                                selected = selectedDeviceId == device.id,
                                onClick = {
                                    selectedDeviceId = device.id
                                    persistView()
                                },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconButton(
                            onClick = {
                                anchorDate = when (granularity) {
                                    StatsGranularity.HOUR -> anchorDate.minusDays(1)
                                    StatsGranularity.DAY -> YearMonth.from(anchorDate).minusMonths(1).atDay(1)
                                    StatsGranularity.MONTH -> anchorDate.minusYears(1)
                                    StatsGranularity.YEAR -> anchorDate
                                }
                            },
                            enabled = canGoPrevious,
                        ) {
                            Icon(Icons.Rounded.ChevronLeft, contentDescription = stringResource(R.string.stats_previous))
                        }
                        Text(periodLabel, fontWeight = FontWeight.SemiBold)
                        IconButton(
                            onClick = {
                                anchorDate = when (granularity) {
                                    StatsGranularity.HOUR -> anchorDate.plusDays(1)
                                    StatsGranularity.DAY -> YearMonth.from(anchorDate).plusMonths(1).atDay(1)
                                    StatsGranularity.MONTH -> anchorDate.plusYears(1)
                                    StatsGranularity.YEAR -> anchorDate
                                }
                            },
                            enabled = canGoNext,
                        ) {
                            Icon(Icons.Rounded.ChevronRight, contentDescription = stringResource(R.string.stats_next))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.stats_total_yield),
                            value = YieldFormatting.whToKwhLabel(totalYield),
                        )
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.stats_peak_power),
                            value = YieldFormatting.wattsLabel(peakPower),
                        )
                    }
                    Text(
                        stringResource(
                            R.string.stats_earnings,
                            YieldFormatting.earningsLabel(totalEarnings, currency),
                        ),
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.stats_chart_unit),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    StatsBarChart(
                        points = points,
                        selectedBucketKey = selectedBucketKey,
                        onBarClick = { key ->
                            selectedBucketKey = if (selectedBucketKey == key) null else key
                        },
                    )
                }
            }
        }
        if (points.isEmpty()) {
            item {
                Text(
                    stringResource(
                        if (periodEvents.isEmpty()) R.string.chart_empty_hint else R.string.chart_empty_with_events,
                    ),
                    color = colors.onSurfaceVariant,
                )
            }
        }
        items(points, key = { it.bucketKey }) { point ->
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceVariant),
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(point.label, fontWeight = FontWeight.SemiBold)
                        point.peakPowerW?.let {
                            Text(
                                YieldFormatting.wattsLabel(it),
                                color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(YieldFormatting.whToKwhLabel(point.yieldWh), fontWeight = FontWeight.Bold)
                        Text(
                            YieldFormatting.earningsLabel(point.earnings, currency),
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            EventListBlock(
                events = selectedBucketEvents ?: periodEvents,
                zoneId = zoneId,
                filter = eventFilter,
                onFilter = { eventFilter = it },
                title = stringResource(R.string.stats_events),
                emptyText = stringResource(R.string.stats_events_empty),
                selectedBucketKey = selectedBucketKey,
                onClearBucket = { selectedBucketKey = null },
            )
        }
    }
}

private fun eventWindow(
    granularity: StatsGranularity,
    anchorDate: LocalDate,
    zoneId: ZoneId,
): Pair<Long, Long> {
    return when (granularity) {
        StatsGranularity.HOUR -> {
            val start = anchorDate.atStartOfDay(zoneId).toEpochSecond()
            val end = anchorDate.plusDays(1).atStartOfDay(zoneId).toEpochSecond() - 1
            start to end
        }
        StatsGranularity.DAY -> {
            val month = YearMonth.from(anchorDate)
            val start = month.atDay(1).atStartOfDay(zoneId).toEpochSecond()
            val end = month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toEpochSecond() - 1
            start to end
        }
        StatsGranularity.MONTH -> {
            val start = LocalDate.of(anchorDate.year, 1, 1).atStartOfDay(zoneId).toEpochSecond()
            val end = LocalDate.of(anchorDate.year + 1, 1, 1).atStartOfDay(zoneId).toEpochSecond() - 1
            start to end
        }
        StatsGranularity.YEAR -> 0L to Instant.now().epochSecond
    }
}

private fun bucketWindow(
    granularity: StatsGranularity,
    bucketKey: String,
    anchorDate: LocalDate,
    zoneId: ZoneId,
): Pair<Long, Long>? {
    return when (granularity) {
        StatsGranularity.HOUR -> {
            val hour = bucketKey.toIntOrNull()?.coerceIn(0, 23) ?: return null
            val local = java.time.LocalDateTime.of(anchorDate, java.time.LocalTime.of(hour, 0))
            val start = local.atZone(zoneId).withEarlierOffsetAtOverlap().toEpochSecond()
            val endExclusive = local.plusHours(1).atZone(zoneId).withLaterOffsetAtOverlap().toEpochSecond()
            start to (endExclusive - 1)
        }
        StatsGranularity.DAY -> {
            val epochDay = bucketKey.toLongOrNull() ?: return null
            val date = LocalDate.ofEpochDay(epochDay)
            val start = date.atStartOfDay(zoneId).toEpochSecond()
            start to (date.plusDays(1).atStartOfDay(zoneId).toEpochSecond() - 1)
        }
        StatsGranularity.MONTH -> {
            val month = runCatching { YearMonth.parse(bucketKey) }.getOrNull() ?: return null
            val start = month.atDay(1).atStartOfDay(zoneId).toEpochSecond()
            start to (month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toEpochSecond() - 1)
        }
        StatsGranularity.YEAR -> {
            val year = bucketKey.toIntOrNull() ?: return null
            val start = LocalDate.of(year, 1, 1).atStartOfDay(zoneId).toEpochSecond()
            start to (LocalDate.of(year + 1, 1, 1).atStartOfDay(zoneId).toEpochSecond() - 1)
        }
    }
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}
