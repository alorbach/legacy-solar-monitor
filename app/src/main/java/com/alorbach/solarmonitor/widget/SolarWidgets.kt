package com.alorbach.solarmonitor.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.alorbach.solarmonitor.MainActivity
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.SolarMonitorApplication
import com.alorbach.solarmonitor.data.model.DeviceDashboardSummary
import com.alorbach.solarmonitor.data.model.StatsPoint
import com.alorbach.solarmonitor.domain.YieldFormatting
import com.alorbach.solarmonitor.ui.parseZoneId
import com.alorbach.solarmonitor.ui.theme.SolarPalette
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

object SolarWidgets {
    suspend fun refreshAll(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        listOf(
            CompactStatsWidget() to CompactStatsWidget::class.java,
            MediumStatsWidget() to MediumStatsWidget::class.java,
            TopDevicesWidget() to TopDevicesWidget::class.java,
        ).forEach { (widget, type) ->
            manager.getGlanceIds(type).forEach { id ->
                widget.update(context, id)
            }
        }
    }
}

private data class WidgetPalette(
    val background: Color,
    val onBackground: Color,
    val onBackgroundMuted: Color,
    val bar: Color,
    val barNow: Color,
)

private data class WidgetSnapshot(
    val summaries: List<DeviceDashboardSummary>,
    val hours: List<StatsPoint>,
    val zone: ZoneId,
)

private fun widgetPalette(night: Boolean) = if (night) {
    WidgetPalette(
        background = SolarPalette.Navy,
        onBackground = SolarPalette.Cream,
        onBackgroundMuted = SolarPalette.Cream.copy(alpha = 0.70f),
        bar = SolarPalette.Cyan.copy(alpha = 0.28f),
        barNow = SolarPalette.Cyan.copy(alpha = 0.45f),
    )
} else {
    WidgetPalette(
        background = SolarPalette.Cream,
        onBackground = SolarPalette.Ink,
        onBackgroundMuted = SolarPalette.Ink.copy(alpha = 0.70f),
        bar = SolarPalette.Cyan.copy(alpha = 0.22f),
        barNow = SolarPalette.Gold.copy(alpha = 0.38f),
    )
}

@Composable
private fun WidgetFrame(
    hours: List<StatsPoint>,
    currentHourKey: String?,
    content: @Composable (onColor: ColorProvider, muted: ColorProvider) -> Unit,
) {
    val context = LocalContext.current
    val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val palette = widgetPalette(night)
    Box(
        modifier = GlanceModifier.fillMaxSize()
            .cornerRadius(16.dp)
            .background(ColorProvider(palette.background))
            .clickable(onClick = actionStartActivity(Intent(context, MainActivity::class.java))),
    ) {
        WidgetHourBars(hours = hours, currentHourKey = currentHourKey, palette = palette)
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(12.dp),
        ) {
            content(ColorProvider(palette.onBackground), ColorProvider(palette.onBackgroundMuted))
        }
    }
}

@Composable
private fun WidgetHourBars(
    hours: List<StatsPoint>,
    currentHourKey: String?,
    palette: WidgetPalette,
) {
    if (hours.isEmpty()) return
    val maxYield = hours.maxOf { it.yieldWh }.coerceAtLeast(1L)
    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.Bottom,
    ) {
        hours.forEach { point ->
            val fraction = (point.yieldWh.toFloat() / maxYield.toFloat()).coerceIn(0f, 1f)
            val barHeight = if (point.yieldWh <= 0L) 3.dp else (4.dp + 92.dp * fraction)
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .height(barHeight)
                    .background(
                        ColorProvider(
                            if (point.bucketKey == currentHourKey) palette.barNow else palette.bar,
                        ),
                    ),
            ) {
            }
        }
    }
}

@Composable
private fun WidgetDeviceHeader(
    name: String,
    updatedAtEpochSeconds: Long?,
    zone: ZoneId,
    muted: ColorProvider,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = TextStyle(color = muted, fontSize = 12.sp),
            modifier = GlanceModifier.defaultWeight(),
            maxLines = 1,
        )
        formatWidgetClock(updatedAtEpochSeconds, zone)?.let { clock ->
            Text(
                clock,
                style = TextStyle(color = muted, fontSize = 11.sp, textAlign = TextAlign.End),
                maxLines = 1,
            )
        }
    }
}

private fun formatWidgetClock(epochSeconds: Long?, zone: ZoneId): String? {
    if (epochSeconds == null) return null
    return Instant.ofEpochSecond(epochSeconds)
        .atZone(zone)
        .format(DateTimeFormatter.ofPattern("HH:mm"))
}

class CompactStatsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = loadWidgetData(context, allDevices = false)
        val summary = snapshot.summaries.firstOrNull()
        val hourKey = LocalDateTime.now(snapshot.zone).hour.toString()
        provideContent {
            WidgetFrame(hours = snapshot.hours, currentHourKey = hourKey) { onColor, muted ->
                WidgetDeviceHeader(
                    name = summary?.deviceName ?: context.getString(R.string.widget_no_device),
                    updatedAtEpochSeconds = summary?.lastUpdateEpochSeconds,
                    zone = snapshot.zone,
                    muted = muted,
                )
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    YieldFormatting.whToKwhLabel(summary?.todayYieldWh),
                    style = TextStyle(color = onColor, fontWeight = FontWeight.Bold, fontSize = 22.sp),
                )
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    YieldFormatting.wattsLabel(summary?.currentPowerW),
                    style = TextStyle(color = muted, fontSize = 13.sp),
                )
            }
        }
    }
}

class MediumStatsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = loadWidgetData(context, allDevices = false)
        val summary = snapshot.summaries.firstOrNull()
        val hourKey = LocalDateTime.now(snapshot.zone).hour.toString()
        provideContent {
            WidgetFrame(hours = snapshot.hours, currentHourKey = hourKey) { onColor, muted ->
                WidgetDeviceHeader(
                    name = summary?.deviceName ?: context.getString(R.string.widget_no_device),
                    updatedAtEpochSeconds = summary?.lastUpdateEpochSeconds,
                    zone = snapshot.zone,
                    muted = muted,
                )
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    YieldFormatting.whToKwhLabel(summary?.todayYieldWh),
                    style = TextStyle(color = onColor, fontWeight = FontWeight.Bold, fontSize = 22.sp),
                )
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    context.getString(R.string.widget_power, YieldFormatting.wattsLabel(summary?.currentPowerW)),
                    style = TextStyle(color = muted, fontSize = 13.sp),
                )
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    context.getString(R.string.widget_month, YieldFormatting.whToKwhLabel(summary?.monthYieldWh)),
                    style = TextStyle(color = onColor, fontSize = 12.sp),
                )
                Text(
                    context.getString(
                        R.string.widget_earnings,
                        YieldFormatting.earningsLabel(summary?.estimatedEarnings ?: 0.0, summary?.currency),
                    ),
                    style = TextStyle(color = muted, fontSize = 12.sp),
                )
            }
        }
    }
}

class TopDevicesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = loadWidgetData(context, allDevices = true)
        val summaries = snapshot.summaries.sortedByDescending { it.currentPowerW ?: 0 }.take(3)
        val hourKey = LocalDateTime.now(snapshot.zone).hour.toString()
        provideContent {
            WidgetFrame(hours = snapshot.hours, currentHourKey = hourKey) { onColor, muted ->
                Text(
                    context.getString(R.string.widget_top_devices),
                    style = TextStyle(color = muted, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(GlanceModifier.height(6.dp))
                if (summaries.isEmpty()) {
                    Text(
                        context.getString(R.string.widget_no_devices),
                        style = TextStyle(color = onColor, fontSize = 13.sp),
                    )
                } else {
                    summaries.forEach { summary ->
                        Row(modifier = GlanceModifier.fillMaxWidth()) {
                            Text(
                                summary.deviceName,
                                style = TextStyle(color = onColor, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                                modifier = GlanceModifier.defaultWeight(),
                            )
                            Text(
                                YieldFormatting.wattsLabel(summary.currentPowerW),
                                style = TextStyle(color = muted, fontSize = 13.sp),
                            )
                        }
                        Spacer(GlanceModifier.height(4.dp))
                    }
                }
            }
        }
    }
}

private suspend fun loadWidgetData(context: Context, allDevices: Boolean): WidgetSnapshot {
    val container = (context.applicationContext as SolarMonitorApplication).container
    val devices = container.repository.observeDevices().first()
    val preferredId = container.settingsStore.settings.first().widgetDeviceId
    val ordered = if (preferredId != null) {
        val preferred = devices.filter { it.id == preferredId }
        preferred + devices.filter { it.id != preferredId }
    } else {
        devices
    }
    val summaries = ordered.mapNotNull { container.repository.getDeviceDashboard(it.id) }
    val ids = if (allDevices) {
        ordered.map { it.id }
    } else {
        listOfNotNull(ordered.firstOrNull()?.id)
    }
    val zone = parseZoneId(ordered.firstOrNull()?.timezone)
    val hours = container.repository.getHourlySeriesToday(ids)
    return WidgetSnapshot(summaries = summaries, hours = hours, zone = zone)
}

class CompactStatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CompactStatsWidget()
}

class MediumStatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MediumStatsWidget()
}

class TopDevicesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TopDevicesWidget()
}
