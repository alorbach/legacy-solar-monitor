package com.alorbach.solarmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.AppContainer
import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import com.alorbach.solarmonitor.domain.EventCatalog
import com.alorbach.solarmonitor.domain.EventSeverity
import java.time.Instant
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
) {
    val colors = MaterialTheme.colorScheme
    val visible = events.filterByEventFilter(filter)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
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
            visible.forEach { event ->
                EventCard(event = event, zoneId = zoneId)
            }
        }
    }
}

@Composable
fun EventCard(
    event: DeviceEventEntity,
    zoneId: ZoneId,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val formatter = remember {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    }
    val whenLabel = Instant.ofEpochSecond(event.timestampEpochSeconds)
        .atZone(zoneId)
        .format(formatter)
    val warning = EventCatalog.severity(event) == EventSeverity.WARNING
    val known = EventCatalog.knownCodeLabelRes(event.eventCode)?.let { stringResource(it) }
    val title = known?.takeIf { it.isNotBlank() } ?: event.tag
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(whenLabel, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                color = if (warning) colors.error else colors.onSurface,
            )
            Text(
                buildString {
                    append(stringResource(EventCatalog.categoryLabelRes(event)))
                    EventCatalog.eventTypeLabelRes(event.eventType)?.let {
                        append(" · ")
                        append(stringResource(it))
                    }
                    append(" · ")
                    append(stringResource(R.string.stats_event_code, event.eventCode))
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            if (event.eventGroup.isNotBlank()) {
                Text(event.eventGroup, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            val oldValue = event.oldValue
            val newValue = event.newValue
            if (!oldValue.isNullOrBlank() || !newValue.isNullOrBlank()) {
                if (oldValue != newValue && (oldValue != "0" || newValue != "0")) {
                    Text(
                        stringResource(R.string.stats_event_old_new, oldValue ?: "—", newValue ?: "—"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun EventRow(
    event: DeviceEventEntity,
    zoneId: ZoneId,
    modifier: Modifier = Modifier,
) {
    EventCard(event, zoneId, modifier)
}
