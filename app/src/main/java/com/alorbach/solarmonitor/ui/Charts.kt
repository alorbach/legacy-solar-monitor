package com.alorbach.solarmonitor.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.model.DailyPoint
import com.alorbach.solarmonitor.data.model.StatsPoint
import com.alorbach.solarmonitor.domain.YieldFormatting
import java.util.Locale
import kotlin.math.min

@Composable
fun ProductionChart(points: List<DailyPoint>) {
    val colors = MaterialTheme.colorScheme
    val maxYield = (points.maxOfOrNull { it.yieldWh } ?: 1L).toFloat()
    val description = if (points.isEmpty()) {
        stringResource(R.string.chart_no_data)
    } else {
        stringResource(
            R.string.chart_description,
            points.size,
            YieldFormatting.whToKwhLabel(points.maxOfOrNull { it.yieldWh }),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .background(colors.surface, RoundedCornerShape(22.dp))
            .padding(16.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (points.isEmpty()) {
            Text(stringResource(R.string.chart_empty_hint))
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val step = size.width / (points.size - 1).coerceAtLeast(1)
                val offsets = points.mapIndexed { index, point ->
                    Offset(
                        x = index * step,
                        y = size.height - ((point.yieldWh / maxYield) * size.height),
                    )
                }
                offsets.zipWithNext().forEach { (start, end) ->
                    drawLine(
                        brush = Brush.linearGradient(listOf(Color(0xFFF4B400), Color(0xFF17212B))),
                        start = start,
                        end = end,
                        strokeWidth = 10f,
                        cap = StrokeCap.Round,
                    )
                }
                offsets.forEach { offset ->
                    drawCircle(Color(0xFFF4B400), radius = 7f, center = offset)
                }
            }
        }
    }
}

@Composable
fun StatsBarChart(points: List<StatsPoint>) {
    val colors = MaterialTheme.colorScheme
    val maxYield = (points.maxOfOrNull { it.yieldWh } ?: 1L).coerceAtLeast(1L).toFloat()
    val outsideLabelColor = colors.onBackground.toArgb()
    val insideLabelColor = Color(0xFF17212B).toArgb()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(colors.surface, RoundedCornerShape(22.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (points.isEmpty()) {
            Text(stringResource(R.string.chart_empty_hint))
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val manyBars = points.size >= 12
                // With many bars, labels go inside — little/no top padding needed.
                val chartTop = if (manyBars) 8.dp.toPx() else 28.dp.toPx()
                val chartHeight = size.height - chartTop
                val barWidth = size.width / (points.size * 1.4f)
                val gap = barWidth * 0.4f
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = when {
                        manyBars -> min(barWidth * 0.85f, 22f).coerceAtLeast(12f)
                        else -> min(barWidth * 0.42f, 28f).coerceAtLeast(18f)
                    }
                }

                points.forEachIndexed { index, point ->
                    val height = if (maxYield <= 0f) 0f else (point.yieldWh / maxYield) * chartHeight
                    val left = index * (barWidth + gap)
                    val top = chartTop + chartHeight - height
                    drawRect(
                        color = Color(0xFFF4B400),
                        topLeft = Offset(left, top),
                        size = Size(barWidth, height.coerceAtLeast(0f)),
                    )

                    val label = compactKwhLabel(point.yieldWh)
                    val centerX = left + barWidth / 2f
                    val labelWidthEstimate = textPaint.measureText(label)
                    // Prefer inside when many/narrow bars, or when the bar is tall enough.
                    val drawVerticalInside = manyBars || barWidth < 36f
                    val canFitVerticalInside = height > labelWidthEstimate + 12f
                    val canFitHorizontalInside = height > textPaint.textSize * 2.2f && barWidth > 28f

                    when {
                        drawVerticalInside && canFitVerticalInside -> {
                            // Rotate inside the bar: text runs upward along the yellow fill.
                            textPaint.color = insideLabelColor
                            val pivotY = top + height / 2f
                            drawContext.canvas.nativeCanvas.save()
                            drawContext.canvas.nativeCanvas.rotate(-90f, centerX, pivotY)
                            drawContext.canvas.nativeCanvas.drawText(
                                label,
                                centerX,
                                pivotY + textPaint.textSize * 0.35f,
                                textPaint,
                            )
                            drawContext.canvas.nativeCanvas.restore()
                        }
                        canFitHorizontalInside -> {
                            textPaint.color = insideLabelColor
                            drawContext.canvas.nativeCanvas.drawText(
                                label,
                                centerX,
                                top + textPaint.textSize + 8f,
                                textPaint,
                            )
                        }
                        else -> {
                            // Short bar: keep label above.
                            textPaint.color = outsideLabelColor
                            val baseline = (top - 8f).coerceAtLeast(textPaint.textSize)
                            if (drawVerticalInside) {
                                drawContext.canvas.nativeCanvas.save()
                                drawContext.canvas.nativeCanvas.rotate(-90f, centerX, baseline)
                                drawContext.canvas.nativeCanvas.drawText(label, centerX, baseline, textPaint)
                                drawContext.canvas.nativeCanvas.restore()
                            } else {
                                drawContext.canvas.nativeCanvas.drawText(label, centerX, baseline, textPaint)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Compact kWh for bar labels, e.g. "496,3" / "0,85" (no unit — unit is implied by the chart). */
private fun compactKwhLabel(yieldWh: Long, locale: Locale = Locale.getDefault()): String {
    val kwh = yieldWh / 1000.0
    return when {
        kwh >= 100 -> String.format(locale, "%.0f", kwh)
        kwh >= 10 -> String.format(locale, "%.1f", kwh)
        else -> String.format(locale, "%.2f", kwh)
    }
}
