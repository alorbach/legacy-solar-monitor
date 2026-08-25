package com.alorbach.solarmonitor.domain

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.model.DailyPoint
import com.alorbach.solarmonitor.data.model.DeviceDashboardSummary
import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import com.alorbach.solarmonitor.data.model.StatsPoint
import com.alorbach.solarmonitor.device.SmaStatusLabels
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object SeriesReportFormat {
    const val MAX_PDF_ROWS = 40

    fun csvEscape(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    fun csvBody(
        points: List<StatsPoint>,
        events: List<DeviceEventEntity>,
        eventZone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        appendLine("label,bucket,yieldWh,peakW,earnings,eventCount")
        points.forEach { point ->
            appendLine(
                listOf(
                    point.label,
                    point.bucketKey,
                    point.yieldWh.toString(),
                    point.peakPowerW?.toString().orEmpty(),
                    point.earnings.toString(),
                    point.eventCount.toString(),
                ).joinToString(",") { csvEscape(it) },
            )
        }
        if (events.isNotEmpty()) {
            appendLine()
            appendLine("timestamp,code,category,tag,old,new")
            val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            events.forEach { event ->
                val whenLabel = Instant.ofEpochSecond(event.timestampEpochSeconds)
                    .atZone(eventZone)
                    .format(formatter)
                appendLine(
                    listOf(
                        whenLabel,
                        event.eventCode.toString(),
                        event.category,
                        event.tag,
                        event.oldValue.orEmpty(),
                        event.newValue.orEmpty(),
                    ).joinToString(",") { csvEscape(it) },
                )
            }
        }
    }

    fun dailyPointsToStats(points: List<DailyPoint>): List<StatsPoint> = points.map { point ->
        StatsPoint(
            label = java.time.LocalDate.ofEpochDay(point.dateEpochDay).toString(),
            bucketKey = point.dateEpochDay.toString(),
            yieldWh = point.yieldWh,
            peakPowerW = null,
            earnings = point.earnings,
        )
    }
}

class ReportExporter(
    private val context: Context,
) {
    fun exportCsv(summary: DeviceDashboardSummary): File {
        val file = reportFile("device-${summary.deviceId}-summary.csv")
        file.writeText(
            buildString {
                appendLine("device,currentPowerW,todayYieldWh,monthYieldWh,yearlyYieldWh,earnings,currency,status,lastUpdate")
                appendLine(
                    listOf(
                        summary.deviceName,
                        summary.currentPowerW ?: "",
                        summary.todayYieldWh ?: "",
                        summary.monthYieldWh ?: "",
                        summary.yearlyYieldWh ?: "",
                        summary.estimatedEarnings,
                        summary.currency ?: "",
                        SmaStatusLabels.displayStatus(context, summary.status) ?: "",
                        summary.lastUpdateEpochSeconds ?: "",
                    ).joinToString(",") { SeriesReportFormat.csvEscape(it.toString()) }
                )
            }
        )
        return file
    }

    fun exportPdf(summary: DeviceDashboardSummary): File {
        val file = reportFile("device-${summary.deviceId}-summary.pdf")
        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas
        val title = Paint().apply { textSize = 22f; isFakeBoldText = true }
        val body = Paint().apply { textSize = 14f }
        canvas.drawText(context.getString(R.string.report_title), 40f, 60f, title)
        canvas.drawText(context.getString(R.string.report_device, summary.deviceName), 40f, 110f, body)
        canvas.drawText(
            context.getString(R.string.report_power, YieldFormatting.wattsLabel(summary.currentPowerW)),
            40f,
            140f,
            body,
        )
        canvas.drawText(
            context.getString(R.string.report_today, YieldFormatting.whToKwhLabel(summary.todayYieldWh)),
            40f,
            170f,
            body,
        )
        canvas.drawText(
            context.getString(R.string.report_month, YieldFormatting.whToKwhLabel(summary.monthYieldWh)),
            40f,
            200f,
            body,
        )
        canvas.drawText(
            context.getString(R.string.report_year, YieldFormatting.whToKwhLabel(summary.yearlyYieldWh)),
            40f,
            230f,
            body,
        )
        canvas.drawText(
            context.getString(
                R.string.report_earnings,
                YieldFormatting.earningsLabel(summary.estimatedEarnings, summary.currency),
            ),
            40f,
            260f,
            body,
        )
        canvas.drawText(
            context.getString(
                R.string.report_status,
                SmaStatusLabels.displayStatus(context, summary.status) ?: "--",
            ),
            40f,
            290f,
            body,
        )
        pdf.finishPage(page)
        file.outputStream().use(pdf::writeTo)
        pdf.close()
        return file
    }

    fun exportSeriesCsv(
        fileStem: String,
        points: List<StatsPoint>,
        events: List<DeviceEventEntity> = emptyList(),
        eventZone: ZoneId = ZoneId.systemDefault(),
    ): File {
        val file = reportFile("$fileStem-series.csv")
        file.writeText(SeriesReportFormat.csvBody(points, events, eventZone))
        return file
    }

    fun exportSeriesPdf(
        fileStem: String,
        deviceLabel: String,
        periodLabel: String,
        currency: String?,
        points: List<StatsPoint>,
        events: List<DeviceEventEntity> = emptyList(),
        eventZone: ZoneId = ZoneId.systemDefault(),
    ): File {
        val file = reportFile("$fileStem-series.pdf")
        val pdf = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f
        val body = Paint().apply { textSize = 11f }
        val titlePaint = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val sectionPaint = Paint().apply { textSize = 14f; isFakeBoldText = true }
        var pageNumber = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        var y = 56f
        fun newPage() {
            pdf.finishPage(page)
            pageNumber += 1
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            y = 56f
        }
        fun drawWrapped(text: String, paint: Paint) {
            val maxWidth = pageWidth - margin * 2
            var remaining = text
            while (remaining.isNotEmpty()) {
                if (y > pageHeight - 48f) newPage()
                val count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
                val end = count.coerceAtMost(remaining.length)
                val line = remaining.substring(0, end)
                canvas.drawText(line, margin, y, paint)
                y += paint.textSize + 4f
                remaining = remaining.substring(line.length)
            }
        }
        drawWrapped(context.getString(R.string.report_title), titlePaint)
        y += 8f
        drawWrapped(context.getString(R.string.report_device, deviceLabel), body)
        drawWrapped(context.getString(R.string.report_period, periodLabel), body)
        val totalYield = points.sumOf { it.yieldWh }
        val totalEarnings = points.sumOf { it.earnings }
        drawWrapped(context.getString(R.string.report_today, YieldFormatting.whToKwhLabel(totalYield)), body)
        drawWrapped(
            context.getString(R.string.report_earnings, YieldFormatting.earningsLabel(totalEarnings, currency)),
            body,
        )
        y += 8f
        val shown = points.take(SeriesReportFormat.MAX_PDF_ROWS)
        if (points.size > shown.size) {
            drawWrapped(
                context.getString(R.string.export_truncated, shown.size, points.size),
                body,
            )
        }
        shown.forEach { point ->
            val peak = point.peakPowerW?.let { YieldFormatting.wattsLabel(it) } ?: "--"
            drawWrapped(
                "${point.label}  ${YieldFormatting.whToKwhLabel(point.yieldWh)}  $peak  " +
                    YieldFormatting.earningsLabel(point.earnings, currency),
                body,
            )
        }
        if (events.isNotEmpty()) {
            y += 8f
            drawWrapped(context.getString(R.string.stats_events), sectionPaint)
            val eventShown = events.take(SeriesReportFormat.MAX_PDF_ROWS)
            val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            eventShown.forEach { event ->
                val whenLabel = Instant.ofEpochSecond(event.timestampEpochSeconds)
                    .atZone(eventZone)
                    .format(formatter)
                drawWrapped("$whenLabel  ${event.tag}  ${event.eventCode}", body)
            }
        }
        pdf.finishPage(page)
        file.outputStream().use(pdf::writeTo)
        pdf.close()
        return file
    }

    fun share(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun reportFile(name: String): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        return File(dir, name)
    }
}
