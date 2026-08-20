package com.alorbach.solarmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.AppContainer
import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import com.alorbach.solarmonitor.domain.EventCatalog
import com.alorbach.solarmonitor.domain.EventListGrouping
import com.alorbach.solarmonitor.domain.EventListItem
import com.alorbach.solarmonitor.domain.EventListRow
import com.alorbach.solarmonitor.domain.EventSeverity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class EventFilter {
    ALL,
    WARNING,
    INFO,
}

@Composable
fun DeviceEventsSection(
    deviceId: Long,
    container: AppContainer,
    dataEpoch: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    deviceName: String? = null,
) {
    var events by remember { mutableStateOf<List<DeviceEventEntity>>(emptyList()) }
    var filter by remember { mutableStateOf(EventFilter.ALL) }
    LaunchedEffect(deviceId, dataEpoch) {
        events = container.repository.getRecentEvents(deviceId, limit = 50)
    }
    EventListBlock(
        events = events,
        zoneId = zoneId,
        filter = filter,
        onFilter = { filter = it },
        title = stringResource(R.string.device_events),
        emptyText = stringResource(R.string.device_events_empty),
        deviceNames = deviceName?.let { mapOf(deviceId to it) }.orEmpty(),
    )
}

fun List<DeviceEventEntity>.filterByEventFilter(filter: EventFilter): List<DeviceEventEntity> = filter { event ->
    when (filter) {
        EventFilter.ALL -> true
        EventFilter.WARNING -> EventCatalog.severity(event) == EventSeverity.WARNING
        EventFilter.INFO -> EventCatalog.severity(event) == EventSeverity.INFO
    }
}

@Composable
fun EventFilterBar(
    filter: EventFilter,
    onFilter: (EventFilter) -> Unit,
    selectedBucketKey: String? = null,
    onClearBucket: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = filter == EventFilter.ALL,
            onClick = { onFilter(EventFilter.ALL) },
            label = { Text(stringResource(R.string.stats_events_all)) },
        )
        FilterChip(
            selected = filter == EventFilter.WARNING,
            onClick = { onFilter(EventFilter.WARNING) },
            label = { Text(stringResource(R.string.stats_events_warning)) },
        )
        FilterChip(
            selected = filter == EventFilter.INFO,
            onClick = { onFilter(EventFilter.INFO) },
            label = { Text(stringResource(R.string.stats_events_info)) },
        )
        if (selectedBucketKey != null && onClearBucket != null) {
            FilterChip(
                selected = true,
                onClick = onClearBucket,
                label = { Text(stringResource(R.string.event_bar_filter_clear)) },
            )
        }
    }
}

@Composable
fun EventListBlock(
    events: List<DeviceEventEntity>,
    zoneId: ZoneId,
    filter: EventFilter,
    onFilter: (EventFilter) -> Unit,
    title: String,
    emptyText: String,
    selectedBucketKey: String? = null,
    onClearBucket: (() -> Unit)? = null,
    deviceNames: Map<Long, String> = emptyMap(),
) {
    val colors = MaterialTheme.colorScheme
    val visible = events.filterByEventFilter(filter)
    val rows = remember(visible, zoneId) { EventListGrouping.rows(visible, zoneId) }
    var expandedKey by remember(visible) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        EventFilterBar(
            filter = filter,
            onFilter = onFilter,
            selectedBucketKey = selectedBucketKey,
            onClearBucket = onClearBucket,
        )
        if (visible.isEmpty()) {
            Text(emptyText, color = colors.onSurfaceVariant)
        } else {
            rows.forEach { row ->
                when (row) {
                    is EventListRow.DateHeader -> EventDateHeader(epochDay = row.epochDay, zoneId = zoneId)
                    is EventListRow.Cluster -> CompactEventRow(
                        item = row.item,
                        zoneId = zoneId,
                        expanded = expandedKey == row.key,
                        onToggle = {
                            expandedKey = if (expandedKey == row.key) null else row.key
                        },
                        deviceNames = deviceNames,
                    )
                }
            }
        }
    }
}

@Composable
fun EventDateHeader(
    epochDay: Long,
    zoneId: ZoneId,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val todayLabel = stringResource(R.string.event_today)
    val label = remember(epochDay, zoneId, todayLabel) {
        val date = LocalDate.ofEpochDay(epochDay)
        if (EventListGrouping.isToday(epochDay, zoneId)) {
            todayLabel
        } else {
            date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
    }
    Text(
        label,
        modifier = modifier.padding(top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = colors.onSurfaceVariant,
    )
}

@Composable
fun CompactEventRow(
    item: EventListItem,
    zoneId: ZoneId,
    expanded: Boolean,
    onToggle: () -> Unit,
    deviceNames: Map<Long, String> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val event = item.representative
    val timeFmt = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val firstTime = remember(event.timestampEpochSeconds, zoneId) {
        Instant.ofEpochSecond(event.timestampEpochSeconds).atZone(zoneId).format(timeFmt)
    }
    val lastTime = remember(item.events.last().timestampEpochSeconds, zoneId) {
        Instant.ofEpochSecond(item.events.last().timestampEpochSeconds).atZone(zoneId).format(timeFmt)
    }
    val warning = EventCatalog.severity(event) == EventSeverity.WARNING
    val known = EventCatalog.knownCodeLabelRes(event.eventCode)?.let { stringResource(it) }
    val title = known?.takeIf { it.isNotBlank() } ?: event.tag
    val oldNew = EventListGrouping.usefulOldNew(event)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (warning) colors.error else colors.onSurfaceVariant,
                        shape = CircleShape,
                    ),
            )
            Text(
                title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (warning) colors.error else colors.onSurface,
            )
            if (item.count > 1) {
                Text(
                    stringResource(R.string.event_repeat_count, item.count),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            Text(
                firstTime,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
        }
        val detailParts = buildList {
            add(stringResource(R.string.stats_event_code, event.eventCode))
            if (oldNew != null) {
                add(stringResource(R.string.stats_event_old_new, oldNew.first, oldNew.second))
            }
        }
        if (detailParts.isNotEmpty()) {
            Text(
                detailParts.joinToString(" · "),
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    buildString {
                        append(stringResource(EventCatalog.categoryLabelRes(event)))
                        EventCatalog.eventTypeLabelRes(event.eventType)?.let {
                            append(" · ")
                            append(stringResource(it))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                if (event.eventGroup.isNotBlank()) {
                    Text(event.eventGroup, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                val deviceLabel = deviceNames[event.deviceId]
                if (!deviceLabel.isNullOrBlank()) {
                    Text(
                        "${stringResource(R.string.event_device_label)}: $deviceLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (oldNew != null) {
                    Text(
                        stringResource(R.string.stats_event_old_new, oldNew.first, oldNew.second),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (item.count > 1) {
                    Text(
                        stringResource(R.string.event_first_last, firstTime, lastTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
