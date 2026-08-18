package com.alorbach.solarmonitor.domain

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.model.DeviceDashboardSummary
import java.io.File

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
                        summary.status ?: "",
                        summary.lastUpdateEpochSeconds ?: "",
                    ).joinToString(",") { csvEscape(it.toString()) }
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
            context.getString(R.string.report_status, summary.status ?: "--"),
            40f,
            290f,
            body,
        )
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

    private fun csvEscape(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
