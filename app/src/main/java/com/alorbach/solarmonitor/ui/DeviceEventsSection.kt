package com.alorbach.solarmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DeviceEventsSection(
    deviceId: Long,
    container: AppContainer,
    dataEpoch: Long,
) {
    val colors = MaterialTheme.colorScheme
    var events by remember { mutableStateOf<List<DeviceEventEntity>>(emptyList()) }
    LaunchedEffect(deviceId, dataEpoch) {
        events = container.repository.getRecentEvents(deviceId, limit = 25)
    }
    val formatter = remember {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.device_events), fontWeight = FontWeight.SemiBold)
        if (events.isEmpty()) {
            Text(stringResource(R.string.device_events_empty), color = colors.onSurfaceVariant)
        } else {
            events.forEach { event ->
                val whenLabel = Instant.ofEpochSecond(event.timestampEpochSeconds)
                    .atZone(ZoneId.systemDefault())
                    .format(formatter)
                Text(
                    "$whenLabel · ${event.eventType} ${event.tag}".trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
