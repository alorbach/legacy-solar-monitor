package com.alorbach.solarmonitor.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.model.DailyPoint
import com.alorbach.solarmonitor.data.model.StatsPoint
import com.alorbach.solarmonitor.domain.YieldFormatting
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ProductionChart(points: List<DailyPoint>) {
    val colors = MaterialTheme.colorScheme
    val maxYield = (points.maxOfOrNull { it.yieldWh } ?: 1L).coerceAtLeast(1L).toFloat()
    val axisLabelColor = colors.onSurfaceVariant.toArgb()
    val axisLineColor = colors.outline.copy(alpha = 0.45f)
    val locale = Locale.getDefault()
    val dateFmt = remember(locale) { DateTimeFormatter.ofPattern("dd.MM", locale) }
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
            .height(if (points.isEmpty()) 72.dp else 220.dp)
            .padding(vertical = 4.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (points.isEmpty()) {
            Text(stringResource(R.string.chart_empty_hint))
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val chartLeft = 8.dp.toPx()
                val chartRight = size.width
                val chartTop = 18.dp.toPx()
                val chartBottom = size.height - 28.dp.toPx()
                val chartHeight = (chartBottom - chartTop).coerceAtLeast(1f)
                val chartWidth = (chartRight - chartLeft).coerceAtLeast(1f)
                val step = chartWidth / (points.size - 1).coerceAtLeast(1)
                val axisTextPx = 12.sp.toPx()
                val peakTextPx = 11.sp.toPx()

                drawLine(
                    color = axisLineColor,
                    start = Offset(chartLeft, chartBottom),
                    end = Offset(chartRight, chartBottom),
                    strokeWidth = 2f,
                )

                val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = axisLabelColor
                    textSize = axisTextPx
                    textAlign = Paint.Align.CENTER
                }
                val yPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = axisLabelColor
                    textSize = peakTextPx
                    textAlign = Paint.Align.LEFT
                }
                // Peak label (kWh) at top-left.
                drawContext.canvas.nativeCanvas.drawText(
                    compactKwhLabel(maxYield.toLong()),
                    chartLeft,
                    chartTop - 4f,
                    yPaint,
                )

                val labelIndexes = listOf(0, points.size / 2, points.lastIndex).distinct()
                labelIndexes.forEach { index ->
                    val x = chartLeft + index * step
                    val label = LocalDate.ofEpochDay(points[index].dateEpochDay).format(dateFmt)
                    axisPaint.textAlign = when (index) {
                        0 -> Paint.Align.LEFT
                        points.lastIndex -> Paint.Align.RIGHT
                        else -> Paint.Align.CENTER
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        x,
                        size.height - 4f,
                        axisPaint,
                    )
                }

                val offsets = points.mapIndexed { index, point ->
                    Offset(
                        x = chartLeft + index * step,
                        y = chartBottom - ((point.yieldWh / maxYield) * chartHeight),
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
    val axisLabelColor = colors.onSurfaceVariant.toArgb()
    val axisLineColor = colors.outline.copy(alpha = 0.45f)
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    // >10 bars: scroll sideways so year/day/hour labels stay readable.
    val enableScroll = points.size > 10
    val minSlotWidth = 52.dp
    val emptyHint = stringResource(R.string.chart_empty_hint)
    val chartDescription = stringResource(
        R.string.chart_description,
        points.size,
        YieldFormatting.whToKwhLabel(points.maxOfOrNull { it.yieldWh }),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .background(colors.surface, RoundedCornerShape(22.dp))
            .padding(16.dp)
            .semantics {
                contentDescription = if (points.isEmpty()) {
                    emptyHint
                } else {
                    chartDescription
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (points.isEmpty()) {
            Text(stringResource(R.string.chart_empty_hint))
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val contentWidth = if (enableScroll) {
                    maxOf(maxWidth, minSlotWidth * points.size)
                } else {
                    maxWidth
                }
                val isScrolling = contentWidth > maxWidth
                val scrollModifier = if (isScrolling) {
                    Modifier.horizontalScroll(scrollState)
                } else {
                    Modifier
                }
                // Auto-jump once per bucket structure (not on every live yield update).
                val structureKey = remember(points) {
                    points.joinToString("\u0000") { it.bucketKey }
                }
                var scrolledStructure by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(structureKey, contentWidth, maxWidth, scrollState.maxValue) {
                    if (!isScrolling) {
                        scrollState.scrollTo(0)
                        scrolledStructure = structureKey
                        return@LaunchedEffect
                    }
                    val maxScroll = scrollState.maxValue
                    if (maxScroll <= 0) return@LaunchedEffect
                    if (scrolledStructure == structureKey) return@LaunchedEffect
                    val firstProd = points.indexOfFirst { it.yieldWh > 0 }.coerceAtLeast(0)
                    val slotPx = with(density) { contentWidth.toPx() } / points.size.coerceAtLeast(1)
                    val viewportPx = with(density) { maxWidth.toPx() }
                    val target = (firstProd * slotPx - viewportPx * 0.08f)
                        .roundToInt()
                        .coerceIn(0, maxScroll)
                    scrollState.scrollTo(target)
                    scrolledStructure = structureKey
                }
                Canvas(
                    modifier = scrollModifier
                        .width(contentWidth)
                        .fillMaxHeight(),
                ) {
                    val manyBars = points.size >= 12 && !isScrolling
                    val longestAxisLabel = points.maxOf { it.label.length }
                    // Hours ("14:00") and years ("2025"): upright vertical is clearer than diagonal.
                    val verticalAxisLabels = longestAxisLabel >= 4 || points.size >= 12
                    val chartTop = if (manyBars) 8.dp.toPx() else 28.dp.toPx()
                    val minSp = 10.sp.toPx()
                    val axisPaintProbe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = if (isScrolling || enableScroll) 11.sp.toPx() else 10.sp.toPx()
                    }
                    val longestAxisWidth = points.maxOf { axisPaintProbe.measureText(it.label) }
                    val chartBottom = if (verticalAxisLabels) {
                        (longestAxisWidth + 16f).coerceAtLeast(48.dp.toPx())
                    } else {
                        24.dp.toPx()
                    }
                    val chartHeight = (size.height - chartTop - chartBottom).coerceAtLeast(1f)
                    val barWidth = size.width / (points.size * 1.4f)
                    val gap = barWidth * 0.4f
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textSize = when {
                            isScrolling || enableScroll -> min(barWidth * 0.55f, 13.sp.toPx()).coerceAtLeast(minSp)
                            manyBars -> min(barWidth * 0.85f, 12.sp.toPx()).coerceAtLeast(minSp)
                            else -> min(barWidth * 0.42f, 14.sp.toPx()).coerceAtLeast(11.sp.toPx())
                        }
                    }
                    val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = axisLabelColor
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                        textSize = when {
                            isScrolling || enableScroll -> min(barWidth * 0.7f, 12.sp.toPx()).coerceIn(minSp, 13.sp.toPx())
                            verticalAxisLabels -> min(barWidth * 0.95f, 12.sp.toPx()).coerceIn(minSp, 12.sp.toPx())
                            else -> min(barWidth * 0.55f, 13.sp.toPx()).coerceIn(minSp, 14.sp.toPx())
                        }
                    }
                    val axisStep = when {
                        isScrolling || enableScroll || points.size <= 14 -> 1
                        points.size <= 24 -> 2
                        else -> (points.size / 12).coerceAtLeast(2)
                    }

                    val axisY = chartTop + chartHeight
                    drawLine(
                        color = axisLineColor,
                        start = Offset(0f, axisY),
                        end = Offset(size.width, axisY),
                        strokeWidth = 2f,
                    )

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
                        val drawVerticalInside = manyBars || (!isScrolling && barWidth < 36f)
                        val canFitVerticalInside = height > labelWidthEstimate + 12f
                        val canFitHorizontalInside = height > textPaint.textSize * 2.2f && barWidth > 28f

                        when {
                            drawVerticalInside && canFitVerticalInside -> {
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

                        val showAxisLabel = index % axisStep == 0 || index == points.lastIndex
                        if (showAxisLabel) {
                            val axisLabel = point.label
                            if (verticalAxisLabels) {
                                // Fully vertical (reads upward), same orientation as in-bar kWh labels.
                                val labelWidth = axisPaint.measureText(axisLabel)
                                val pivotY = axisY + 8f + labelWidth / 2f
                                drawContext.canvas.nativeCanvas.save()
                                drawContext.canvas.nativeCanvas.rotate(-90f, centerX, pivotY)
                                drawContext.canvas.nativeCanvas.drawText(
                                    axisLabel,
                                    centerX,
                                    pivotY + axisPaint.textSize * 0.35f,
                                    axisPaint,
                                )
                                drawContext.canvas.nativeCanvas.restore()
                            } else {
                                drawContext.canvas.nativeCanvas.drawText(
                                    axisLabel,
                                    centerX,
                                    axisY + axisPaint.textSize + 6f,
                                    axisPaint,
                                )
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
