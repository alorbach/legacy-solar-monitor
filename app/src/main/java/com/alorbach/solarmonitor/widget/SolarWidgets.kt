package com.alorbach.solarmonitor.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
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
import com.alorbach.solarmonitor.MainActivity
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.SolarMonitorApplication
import com.alorbach.solarmonitor.data.model.DeviceDashboardSummary
import com.alorbach.solarmonitor.domain.YieldFormatting
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

@Composable
private fun WidgetFrame(title: String, body: @Composable (ColorProvider) -> Unit) {
    val context = LocalContext.current
    val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val background = if (night) Color(0xFF1B2430) else Color(0xFFF5F1E8)
    val onBackground = if (night) Color(0xFFF4F0E8) else Color(0xFF17212B)
    val textColor = ColorProvider(onBackground)
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(ColorProvider(background))
            .clickable(onClick = actionStartActivity(Intent(context, MainActivity::class.java)))
            .padding(12.dp)
    ) {
        Text(title, style = TextStyle(fontWeight = FontWeight.Bold, color = textColor))
        Spacer(GlanceModifier.height(8.dp))
        body(textColor)
    }
}

@Composable
private fun WidgetLine(text: String, color: ColorProvider) {
    Text(text, style = TextStyle(color = color))
}

class CompactStatsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summary = loadSummaries(context).firstOrNull()
        provideContent {
            WidgetFrame(title = summary?.deviceName ?: context.getString(R.string.widget_no_device)) { color ->
                WidgetLine(YieldFormatting.wattsLabel(summary?.currentPowerW), color)
                WidgetLine(
                    context.getString(R.string.widget_today, YieldFormatting.whToKwhLabel(summary?.todayYieldWh)),
                    color,
                )
            }
        }
    }
}

class MediumStatsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summary = loadSummaries(context).firstOrNull()
        provideContent {
            WidgetFrame(title = summary?.deviceName ?: context.getString(R.string.widget_no_device)) { color ->
                WidgetLine(
                    context.getString(R.string.widget_power, YieldFormatting.wattsLabel(summary?.currentPowerW)),
                    color,
                )
                WidgetLine(
                    context.getString(R.string.widget_today, YieldFormatting.whToKwhLabel(summary?.todayYieldWh)),
                    color,
                )
                WidgetLine(
                    context.getString(R.string.widget_month, YieldFormatting.whToKwhLabel(summary?.monthYieldWh)),
                    color,
                )
                WidgetLine(
                    context.getString(
                        R.string.widget_earnings,
                        YieldFormatting.earningsLabel(summary?.estimatedEarnings ?: 0.0, summary?.currency),
                    ),
                    color,
                )
            }
        }
    }
}

class TopDevicesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summaries = loadSummaries(context).sortedByDescending { it.currentPowerW ?: 0 }.take(3)
        provideContent {
            WidgetFrame(title = context.getString(R.string.widget_top_devices)) { color ->
                summaries.forEach { summary ->
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        WidgetLine(summary.deviceName, color)
                    }
                    WidgetLine(YieldFormatting.wattsLabel(summary.currentPowerW), color)
                }
            }
        }
    }
}

private suspend fun loadSummaries(context: Context): List<DeviceDashboardSummary> {
    val container = (context.applicationContext as SolarMonitorApplication).container
    val devices = container.repository.observeDevices().first()
    val preferredId = container.settingsStore.settings.first().widgetDeviceId
    val ordered = if (preferredId != null) {
        val preferred = devices.filter { it.id == preferredId }
        preferred + devices.filter { it.id != preferredId }
    } else {
        devices
    }
    return ordered.mapNotNull { container.repository.getDeviceDashboard(it.id) }
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
