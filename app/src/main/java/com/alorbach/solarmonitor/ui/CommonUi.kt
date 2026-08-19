package com.alorbach.solarmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun MetricTile(modifier: Modifier = Modifier, label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .background(colors.surfaceVariant, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun StatusBadge(message: String, active: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = message,
        modifier = Modifier
            .background(
                if (active) colors.tertiaryContainer else colors.surfaceVariant,
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = if (active) colors.onTertiaryContainer else colors.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
fun DeviceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
fun EmptyStateCard(title: String, body: String) {
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

fun formatEpochSeconds(value: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
    return Instant.ofEpochSecond(value)
        .atZone(zoneId)
        .format(formatter)
}

fun parseZoneId(timezone: String?): ZoneId =
    runCatching { ZoneId.of(timezone?.takeIf { it.isNotBlank() } ?: ZoneId.systemDefault().id) }
        .getOrDefault(ZoneId.systemDefault())

@Preview(showBackground = true)
@Composable
private fun MetricTilePreview() {
    MaterialTheme {
        MetricTile(label = "Today", value = "12.345 kWh")
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyStateCardPreview() {
    MaterialTheme {
        EmptyStateCard(title = "No devices yet", body = "Add a Bluetooth SMA inverter to start.")
    }
}

@Preview(showBackground = true)
@Composable
private fun DeviceChipPreview() {
    MaterialTheme {
        DeviceChip(label = "SMA Sunny Boy", selected = true, onClick = {})
    }
}
