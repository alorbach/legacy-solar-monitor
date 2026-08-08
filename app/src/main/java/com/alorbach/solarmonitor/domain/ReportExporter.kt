package com.alorbach.solarmonitor.domain

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.alorbach.solarmonitor.data.model.DeviceDashboardSummary
import java.io.File

class ReportExporter(
    private val context: Context,
) {
    fun exportCsv(summary: DeviceDashboardSummary): File {
        val file = File(context.getDir("reports", Context.MODE_PRIVATE), "device-${summary.deviceId}-summary.csv")
        file.parentFile?.mkdirs()
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
        val file = File(context.getDir("reports", Context.MODE_PRIVATE), "device-${summary.deviceId}-summary.pdf")
        file.parentFile?.mkdirs()
        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas
        val title = Paint().apply { textSize = 22f; isFakeBoldText = true }
        val body = Paint().apply { textSize = 14f }
        canvas.drawText("SMA Solar Monitor Report", 40f, 60f, title)
        canvas.drawText("Device: ${summary.deviceName}", 40f, 110f, body)
        canvas.drawText("Current power: ${summary.currentPowerW ?: "--"} W", 40f, 140f, body)
        canvas.drawText("Today: ${YieldFormatting.whToKwhLabel(summary.todayYieldWh)}", 40f, 170f, body)
        canvas.drawText("Month: ${YieldFormatting.whToKwhLabel(summary.monthYieldWh)}", 40f, 200f, body)
        canvas.drawText("Year: ${YieldFormatting.whToKwhLabel(summary.yearlyYieldWh)}", 40f, 230f, body)
        canvas.drawText("Earnings: ${"%.2f".format(summary.estimatedEarnings)} ${summary.currency ?: ""}", 40f, 260f, body)
        canvas.drawText("Status: ${summary.status ?: "--"}", 40f, 290f, body)
        pdf.finishPage(page)
        file.outputStream().use(pdf::writeTo)
        pdf.close()
        return file
    }

    private fun csvEscape(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
