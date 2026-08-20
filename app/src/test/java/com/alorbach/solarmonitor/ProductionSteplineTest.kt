package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.domain.ProductionStepline
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductionSteplineTest {
    @Test
    fun tapOnHorizontalStepSelectsThatDayNotTheNext() {
        val chartLeft = 44f
        val step = 100f
        val lastIndex = 3
        assertEquals(0, ProductionStepline.slotIndex(chartLeft, chartLeft, step, lastIndex))
        assertEquals(0, ProductionStepline.slotIndex(chartLeft + 99f, chartLeft, step, lastIndex))
        assertEquals(1, ProductionStepline.slotIndex(chartLeft + 100f, chartLeft, step, lastIndex))
        assertEquals(3, ProductionStepline.slotIndex(chartLeft + 300f, chartLeft, step, lastIndex))
    }

    @Test
    fun singlePointAlwaysSelectsIndexZero() {
        assertEquals(0, ProductionStepline.slotIndex(200f, 44f, 400f, 0))
    }

    @Test
    fun shortStepStaysInsideChart() {
        assertEquals(60f, ProductionStepline.singlePointStepEndX(44f, 200f, 16f))
        assertEquals(200f, ProductionStepline.singlePointStepEndX(190f, 200f, 16f))
    }
}
