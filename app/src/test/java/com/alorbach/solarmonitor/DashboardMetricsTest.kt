package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.model.MonthAggregateEntity
import com.alorbach.solarmonitor.domain.DashboardMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardMetricsTest {
    @Test
    fun currentPowerIsNullWhenSampleIsStale() {
        val now = 1_700_000_000L
        val power = DashboardMetrics.currentPowerW(
            latestPac = 3200,
            sampleEpochSeconds = now - DashboardMetrics.STALE_POWER_SECONDS - 1,
            nowEpochSeconds = now,
        )
        assertNull(power)
    }

    @Test
    fun currentPowerUsesFreshSample() {
        val now = 1_700_000_000L
        val power = DashboardMetrics.currentPowerW(
            latestPac = 3200,
            sampleEpochSeconds = now - 60,
            nowEpochSeconds = now,
        )
        assertEquals(3200, power)
    }

    @Test
    fun monthYieldDoesNotFallBackToPreviousMonth() {
        val months = listOf(
            MonthAggregateEntity(deviceId = 1, monthKey = "2024-04", totalYieldWh = 50_000, dayYieldWh = 50_000),
        )
        assertNull(DashboardMetrics.monthYieldWh("2024-05", months))
        assertEquals(50_000L, DashboardMetrics.monthYieldWh("2024-04", months))
    }
}
