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

    @Test
    fun todayYieldIgnoresStaleETodayFromYesterday() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val today = java.time.LocalDate.of(2026, 8, 19)
        val yesterdaySample = today.minusDays(1).atTime(18, 0).atZone(zone).toEpochSecond()
        val yield = DashboardMetrics.todayYieldWh(
            latestETodayWh = 12_000L,
            sampleEpochSeconds = yesterdaySample,
            todayEpochDay = today.toEpochDay(),
            zoneId = zone,
            dayAggregateYieldWh = 3_500L,
        )
        assertEquals(3_500L, yield)
    }

    @Test
    fun todayYieldUsesETodayWhenSampleIsToday() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val today = java.time.LocalDate.of(2026, 8, 19)
        val sample = today.atTime(10, 0).atZone(zone).toEpochSecond()
        val yield = DashboardMetrics.todayYieldWh(
            latestETodayWh = 12_000L,
            sampleEpochSeconds = sample,
            todayEpochDay = today.toEpochDay(),
            zoneId = zone,
            dayAggregateYieldWh = 3_500L,
        )
        assertEquals(12_000L, yield)
    }
}
