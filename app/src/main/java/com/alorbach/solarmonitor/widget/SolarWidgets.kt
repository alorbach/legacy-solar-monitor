package com.alorbach.solarmonitor.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.SolarMonitorApplication
import com.alorbach.solarmonitor.data.model.DeviceDashboardSummary
import com.alorbach.solarmonitor.domain.YieldFormatting
import kotlinx.coroutines.flow.first

@Composable
private fun WidgetFrame(title: String, body: @Composable () -> Unit) {
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(ColorProvider(0xFFF5F1E8.toInt()))
            .padding(12.dp)
    ) {
        Text(title, style = TextStyle(fontWeight = FontWeight.Bold))
        Spacer(GlanceModifier.height(8.dp))
        body()
    }
}

class CompactStatsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summary = loadSummaries(context).firstOrNull()
        provideContent {
            WidgetFrame(title = summary?.deviceName ?: context.getString(R.string.widget_no_device)) {
                Text(YieldFormatting.wattsLabel(summary?.currentPowerW))
                Text(context.getString(R.string.widget_today, YieldFormatting.whToKwhLabel(summary?.todayYieldWh)))
            }
        }
    }
}

class MediumStatsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summary = loadSummaries(context).firstOrNull()
        provideContent {
            WidgetFrame(title = summary?.deviceName ?: context.getString(R.string.widget_no_device)) {
                Text(context.getString(R.string.widget_power, YieldFormatting.wattsLabel(summary?.currentPowerW)))
                Text(context.getString(R.string.widget_today, YieldFormatting.whToKwhLabel(summary?.todayYieldWh)))
                Text(context.getString(R.string.widget_month, YieldFormatting.whToKwhLabel(summary?.monthYieldWh)))
                Text(
                    context.getString(
                        R.string.widget_earnings,
                        YieldFormatting.earningsLabel(summary?.estimatedEarnings ?: 0.0, summary?.currency),
                    )
                )
            }
        }
    }
}

class TopDevicesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summaries = loadSummaries(context).sortedByDescending { it.currentPowerW ?: 0 }.take(3)
        provideContent {
            WidgetFrame(title = context.getString(R.string.widget_top_devices)) {
                summaries.forEach { summary ->
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Text(summary.deviceName)
                    }
                    Text(YieldFormatting.wattsLabel(summary.currentPowerW))
                }
            }
        }
    }
}

private suspend fun loadSummaries(context: Context): List<DeviceDashboardSummary> {
    val repository = (context.applicationContext as SolarMonitorApplication).container.repository
    val devices = repository.observeDevices().first()
    return devices.mapNotNull { repository.getDeviceDashboard(it.id) }
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
