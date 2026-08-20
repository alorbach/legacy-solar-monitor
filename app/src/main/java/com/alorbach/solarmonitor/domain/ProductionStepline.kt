package com.alorbach.solarmonitor.domain

import kotlin.math.floor

/** Geometry for the dashboard filled step-after production chart. */
object ProductionStepline {
    fun step(chartWidth: Float, pointCount: Int): Float =
        chartWidth / (pointCount - 1).coerceAtLeast(1)

    /**
     * Map a tap x to the day whose horizontal step contains it.
     * Uses floor so a tap on the run from day i to day i+1 selects day i.
     */
    fun slotIndex(tapX: Float, chartLeft: Float, step: Float, lastIndex: Int): Int {
        if (lastIndex <= 0 || step <= 0f) return 0
        val rel = tapX - chartLeft
        return floor((rel / step).toDouble()).toInt().coerceIn(0, lastIndex)
    }

    fun singlePointStepEndX(markerX: Float, chartRight: Float, shortStepPx: Float): Float =
        (markerX + shortStepPx.coerceAtLeast(1f)).coerceAtMost(chartRight)
}
