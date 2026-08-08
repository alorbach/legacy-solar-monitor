package com.alorbach.solarmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
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
import com.alorbach.solarmonitor.data.model.DeviceProfileEntity
import com.alorbach.solarmonitor.data.model.StatsGranularity
import com.alorbach.solarmonitor.data.model.StatsPoint
import com.alorbach.solarmonitor.data.settings.AppSettings
import com.alorbach.solarmonitor.domain.YieldFormatting
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

    LaunchedEffect(granularity, selectedDeviceId, anchorDate, dataEpoch, devices) {
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
                        Button(
                            onClick = {
                                anchorDate = when (granularity) {
                                    StatsGranularity.HOUR -> anchorDate.minusDays(1)
                                    StatsGranularity.DAY -> YearMonth.from(anchorDate).minusMonths(1).atDay(1)
                                    StatsGranularity.MONTH -> anchorDate.minusYears(1)
                                    StatsGranularity.YEAR -> anchorDate
                                }
                            },
                            enabled = granularity != StatsGranularity.YEAR,
                        ) {
                            Icon(Icons.Rounded.ChevronLeft, contentDescription = stringResource(R.string.stats_previous))
                        }
                        Text(periodLabel, fontWeight = FontWeight.SemiBold)
                        Button(
                            onClick = {
                                anchorDate = when (granularity) {
                                    StatsGranularity.HOUR -> anchorDate.plusDays(1)
                                    StatsGranularity.DAY -> YearMonth.from(anchorDate).plusMonths(1).atDay(1)
                                    StatsGranularity.MONTH -> anchorDate.plusYears(1)
                                    StatsGranularity.YEAR -> anchorDate
                                }
                            },
                            enabled = granularity != StatsGranularity.YEAR,
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
                            YieldFormatting.earningsLabel(totalEarnings, null),
                        ),
                        color = colors.onSurfaceVariant,
                    )
                    StatsBarChart(points)
                }
            }
        }
        if (points.isEmpty()) {
            item {
                Text(stringResource(R.string.chart_empty_hint), color = colors.onSurfaceVariant)
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
                            YieldFormatting.earningsLabel(point.earnings, null),
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
