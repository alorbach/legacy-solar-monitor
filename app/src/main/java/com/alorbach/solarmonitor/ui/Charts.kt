package com.alorbach.solarmonitor.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.model.DailyPoint
import com.alorbach.solarmonitor.data.model.StatsPoint
import com.alorbach.solarmonitor.domain.StatsSeriesFill
import com.alorbach.solarmonitor.domain.YieldFormatting
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ProductionChart(
    points: List<DailyPoint>,
    selectedEpochDay: Long? = null,
    onPointClick: ((DailyPoint) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val maxYield = (points.maxOfOrNull { it.yieldWh } ?: 1L).coerceAtLeast(1L).toFloat()
    val midYield = (maxYield / 2f).toLong()
    val axisLabelColor = colors.onSurfaceVariant.toArgb()
    val axisLineColor = colors.outline.copy(alpha = 0.45f)
    val selectedColor = colors.tertiary
    val lineColor = colors.secondary
    val locale = Locale.getDefault()
    val dateFmt = remember(locale) {
        if (locale.language == "de") {
            DateTimeFormatter.ofPattern("dd.MM", locale)
        } else {
            DateTimeFormatter.ofPattern("MMM d", locale)
        }
    }
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
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (onPointClick == null) {
                            Modifier
                        } else {
                            Modifier.pointerInput(points) {
                                detectTapGestures { offset ->
                                    val yAxis = 44.dp.toPx()
                                    val chartLeft = yAxis
                                    val chartRight = size.width
                                    val chartWidth = (chartRight - chartLeft).coerceAtLeast(1f)
                                    val step = chartWidth / (points.size - 1).coerceAtLeast(1)
                                    val index = ((offset.x - chartLeft) / step).roundToInt()
                                        .coerceIn(0, points.lastIndex)
                                    onPointClick(points[index])
                                }
                            }
                        },
                    ),
            ) {
                val yAxisWidth = 44.dp.toPx()
                val chartLeft = yAxisWidth
                val chartRight = size.width
                val chartTop = 18.dp.toPx()
                val chartBottom = size.height - 28.dp.toPx()
                val chartHeight = (chartBottom - chartTop).coerceAtLeast(1f)
                val chartWidth = (chartRight - chartLeft).coerceAtLeast(1f)
                val step = chartWidth / (points.size - 1).coerceAtLeast(1)

                val yPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = axisLabelColor
                    textSize = 11.sp.toPx()
                    textAlign = Paint.Align.RIGHT
                }
                val labelX = chartLeft - 6.dp.toPx()
                drawContext.canvas.nativeCanvas.drawText(
                    YieldFormatting.compactKwhNumber(maxYield.toLong()),
                    labelX,
                    chartTop + yPaint.textSize * 0.35f,
                    yPaint,
                )
                drawContext.canvas.nativeCanvas.drawText(
                    YieldFormatting.compactKwhNumber(midYield),
                    labelX,
                    chartTop + chartHeight / 2f + yPaint.textSize * 0.35f,
                    yPaint,
                )
                drawContext.canvas.nativeCanvas.drawText(
                    YieldFormatting.compactKwhNumber(0L),
                    labelX,
                    chartBottom,
                    yPaint,
                )
                drawLine(
                    color = axisLineColor.copy(alpha = 0.22f),
                    start = Offset(chartLeft, chartTop + chartHeight / 2f),
                    end = Offset(chartRight, chartTop + chartHeight / 2f),
                    strokeWidth = 1.5f,
                )
                drawLine(
                    color = axisLineColor,
                    start = Offset(chartLeft, chartBottom),
                    end = Offset(chartRight, chartBottom),
                    strokeWidth = 2f,
                )

                val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = axisLabelColor
                    textSize = 12.sp.toPx()
                    textAlign = Paint.Align.CENTER
                }
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
                        color = lineColor,
                        start = start,
                        end = end,
                        strokeWidth = 10f,
                        cap = StrokeCap.Round,
                    )
                }
                points.forEachIndexed { index, point ->
                    val selected = point.dateEpochDay == selectedEpochDay
                    drawCircle(
                        color = if (selected) selectedColor else lineColor,
                        radius = if (selected) 10f else 7f,
                        center = offsets[index],
                    )
                }
            }
        }
    }
}

@Composable
fun StatsBarChart(
    points: List<StatsPoint>,
    selectedBucketKey: String? = null,
    onBarClick: ((String) -> Unit)? = null,
    onBarDoubleClick: ((String) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val maxYield = (points.maxOfOrNull { it.yieldWh } ?: 1L).coerceAtLeast(1L).toFloat()
    val midYield = (maxYield / 2f).toLong()
    val outsideLabelColor = colors.onBackground.toArgb()
    val insideLabelColor = colors.onSecondary.toArgb()
    val axisLabelColor = colors.onSurfaceVariant.toArgb()
    val axisLineColor = colors.outline.copy(alpha = 0.45f)
    val eventMarkerColor = colors.error
    val selectedBarColor = colors.tertiary
    val defaultBarColor = colors.secondary
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val emptyHint = stringResource(R.string.chart_empty_hint)
    val chartDescription = stringResource(
        R.string.chart_description,
        points.size,
        YieldFormatting.whToKwhLabel(points.maxOfOrNull { it.yieldWh }),
    )
    val yAxisWidth = 44.dp
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
                val barViewport = (maxWidth - yAxisWidth).coerceAtLeast(1.dp)
                val visibleBars = StatsSeriesFill.VISIBLE_BARS
                val slotWidth = barViewport / visibleBars
                val contentWidth = slotWidth * points.size
                val isScrolling = contentWidth > barViewport
                val manyBars = points.size >= StatsSeriesFill.VISIBLE_BARS && !isScrolling
                val hasEventMarkers = points.any { it.eventCount > 0 }
                val longestAxisLabel = points.maxOf { it.label.length }
                val verticalAxisLabels = longestAxisLabel >= 4 && (isScrolling || points.size >= 10)
                val chartTopPx = with(density) {
                    val base = if (manyBars) 8.dp else 28.dp
                    (if (hasEventMarkers) base + 10.dp else base).toPx()
                }
                val axisProbe = remember(points, isScrolling) {
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = with(density) { if (isScrolling) 11.sp.toPx() else 10.sp.toPx() }
                    }
                }
                val longestAxisWidth = points.maxOf { axisProbe.measureText(it.label) }
                val chartBottomPx = with(density) {
                    if (verticalAxisLabels) {
                        (longestAxisWidth + 16f).coerceAtLeast(48.dp.toPx())
                    } else {
                        28.dp.toPx()
                    }
                }
                val scrollModifier = if (isScrolling) {
                    Modifier.horizontalScroll(scrollState)
                } else {
                    Modifier
                }
                val structureKey = remember(points) {
                    points.joinToString("\u0000") { it.bucketKey }
                }
                var scrolledStructure by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(structureKey, contentWidth, barViewport, scrollState.maxValue) {
                    if (!isScrolling) {
                        scrollState.scrollTo(0)
                        scrolledStructure = structureKey
                        return@LaunchedEffect
                    }
                    val maxScroll = scrollState.maxValue
                    if (maxScroll <= 0) return@LaunchedEffect
                    if (scrolledStructure == structureKey) return@LaunchedEffect
                    val firstProd = points.indexOfFirst { it.yieldWh > 0 || it.eventCount > 0 }.coerceAtLeast(0)
                    val slotPx = with(density) { contentWidth.toPx() } / points.size.coerceAtLeast(1)
                    val viewportPx = with(density) { barViewport.toPx() }
                    val target = (firstProd * slotPx - viewportPx * 0.08f)
                        .roundToInt()
                        .coerceIn(0, maxScroll)
                    scrollState.scrollTo(target)
                    scrolledStructure = structureKey
                }
                Row(modifier = Modifier.fillMaxSize()) {
                    Canvas(
                        modifier = Modifier
                            .width(yAxisWidth)
                            .fillMaxHeight(),
                    ) {
                        val chartHeight = (size.height - chartTopPx - chartBottomPx).coerceAtLeast(1f)
                        val axisY = chartTopPx + chartHeight
                        val yPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = axisLabelColor
                            textSize = 10.sp.toPx()
                            textAlign = Paint.Align.RIGHT
                        }
                        val labelX = size.width - 4.dp.toPx()
                        drawContext.canvas.nativeCanvas.drawText(
                            YieldFormatting.compactKwhNumber(maxYield.toLong()),
                            labelX,
                            chartTopPx + yPaint.textSize * 0.35f,
                            yPaint,
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            YieldFormatting.compactKwhNumber(midYield),
                            labelX,
                            chartTopPx + chartHeight / 2f + yPaint.textSize * 0.35f,
                            yPaint,
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            YieldFormatting.compactKwhNumber(0L),
                            labelX,
                            axisY,
                            yPaint,
                        )
                    }
                    Canvas(
                        modifier = scrollModifier
                            .width(contentWidth)
                            .fillMaxHeight()
                            .then(
                                if (onBarClick == null && onBarDoubleClick == null) {
                                    Modifier
                                } else {
                                    Modifier.pointerInput(points, contentWidth) {
                                        val inset = with(density) { 12.dp.toPx() }
                                        detectTapGestures(
                                            onTap = { offset ->
                                                val index = barIndexAt(offset.x, size.width.toFloat(), points.size, inset)
                                                if (index in points.indices) {
                                                    onBarClick?.invoke(points[index].bucketKey)
                                                }
                                            },
                                            onDoubleTap = { offset ->
                                                val index = barIndexAt(offset.x, size.width.toFloat(), points.size, inset)
                                                if (index in points.indices) {
                                                    onBarDoubleClick?.invoke(points[index].bucketKey)
                                                }
                                            },
                                        )
                                    }
                                },
                            ),
                    ) {
                    val minSp = 10.sp.toPx()
                    val chartTop = chartTopPx
                    val chartBottom = chartBottomPx
                    val inset = 12.dp.toPx()
                    val usableWidth = (size.width - inset * 2f).coerceAtLeast(1f)
                    val slot = usableWidth / points.size.coerceAtLeast(1)
                    val chartHeight = (size.height - chartTop - chartBottom).coerceAtLeast(1f)
                    val barWidth = slot * 0.62f
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textSize = when {
                            isScrolling -> min(barWidth * 0.55f, 13.sp.toPx()).coerceAtLeast(minSp)
                            manyBars -> min(barWidth * 0.85f, 12.sp.toPx()).coerceAtLeast(minSp)
                            else -> min(barWidth * 0.42f, 14.sp.toPx()).coerceAtLeast(11.sp.toPx())
                        }
                    }
                    val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = axisLabelColor
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                        textSize = when {
                            isScrolling -> min(barWidth * 0.7f, 12.sp.toPx()).coerceIn(minSp, 13.sp.toPx())
                            verticalAxisLabels -> min(barWidth * 0.95f, 12.sp.toPx()).coerceIn(minSp, 12.sp.toPx())
                            else -> min(barWidth * 0.55f, 13.sp.toPx()).coerceIn(minSp, 14.sp.toPx())
                        }
                    }
                    val axisStep = when {
                        isScrolling || points.size <= 14 -> 1
                        points.size <= 24 -> 2
                        else -> (points.size / 12).coerceAtLeast(2)
                    }

                    val axisY = chartTop + chartHeight
                    val gridColor = axisLineColor.copy(alpha = 0.22f)
                    listOf(0.5f, 1f).forEach { fraction ->
                        val y = axisY - chartHeight * fraction
                        drawLine(
                            color = gridColor,
                            start = Offset(inset, y),
                            end = Offset(size.width - inset, y),
                            strokeWidth = 1.5f,
                        )
                    }
                    drawLine(
                        color = axisLineColor,
                        start = Offset(inset, axisY),
                        end = Offset(size.width - inset, axisY),
                        strokeWidth = 2f,
                    )

                    points.forEachIndexed { index, point ->
                        val height = if (maxYield <= 0f) 0f else (point.yieldWh / maxYield) * chartHeight
                        val left = inset + index * slot + (slot - barWidth) / 2f
                        val top = chartTop + chartHeight - height
                        drawRect(
                            color = if (point.bucketKey == selectedBucketKey) selectedBarColor else defaultBarColor,
                            topLeft = Offset(left, top),
                            size = Size(barWidth, height.coerceAtLeast(0f)),
                        )
                        if (point.eventCount > 0) {
                            val markerRadius = 5.dp.toPx()
                            drawCircle(
                                color = eventMarkerColor,
                                radius = markerRadius,
                                center = Offset(
                                    left + barWidth / 2f,
                                    (top - markerRadius - 2.dp.toPx()).coerceAtLeast(markerRadius + 1f),
                                ),
                            )
                        }

                        val label = YieldFormatting.compactKwhNumber(point.yieldWh)
                        val centerX = left + barWidth / 2f
                        val labelWidthEstimate = textPaint.measureText(label)
                        val drawVerticalInside = manyBars || (!isScrolling && barWidth < 36f)
                        val canFitVerticalInside = height > labelWidthEstimate + 12f
                        val canFitHorizontalInside = height > textPaint.textSize * 2.2f && barWidth > 28f
                        val skipTinyLabel = height < textPaint.textSize * 1.4f &&
                            point.bucketKey != selectedBucketKey

                        when {
                            skipTinyLabel -> Unit
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
}

private fun barIndexAt(x: Float, widthPx: Float, count: Int, insetPx: Float): Int {
    val usableWidth = (widthPx - insetPx * 2f).coerceAtLeast(1f)
    val slot = usableWidth / count.coerceAtLeast(1)
    return ((x - insetPx) / slot).toInt()
}

