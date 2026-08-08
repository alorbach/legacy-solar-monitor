package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.model.SpotSampleEntity
import com.alorbach.solarmonitor.domain.StatisticsAggregator
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsAggregatorTest {
    private val zone = ZoneOffset.UTC

    @Test
    fun hourBucketsUseCumulativeDeltaNotMaxMinusMin() {
        // 10:00 -> 1000 Wh, 10:30 -> 1500 Wh, 11:00 -> 2000 Wh
        val samples = listOf(
            sample(epoch("2024-01-01T10:00:00Z"), 1000),
            sample(epoch("2024-01-01T10:30:00Z"), 1500),
            sample(epoch("2024-01-01T11:00:00Z"), 2000),
        )
        val hours = StatisticsAggregator.hourAggregatesFromSamples(1L, samples, zone)
        assertEquals(2, hours.size)
        // First hour has no prior baseline: use first-in-hour reading -> 1500 - 1000
        assertEquals(500L, hours[0].yieldWh)
        // Second hour: 2000 - 1500 (last of previous hour)
        assertEquals(500L, hours[1].yieldWh)
    }

    @Test
    fun firstHourCapturesIntraHourProductionWithoutPriorBaseline() {
        val samples = listOf(
            sample(epoch("2024-01-01T10:00:00Z"), 1000),
            sample(epoch("2024-01-01T10:45:00Z"), 1800),
        )
        val hours = StatisticsAggregator.hourAggregatesFromSamples(1L, samples, zone)
        assertEquals(1, hours.size)
        assertEquals(800L, hours[0].yieldWh)
    }

    @Test
    fun counterResetDoesNotProduceNegativeYield() {
        val samples = listOf(
            sample(epoch("2024-01-01T10:00:00Z"), 5000),
            sample(epoch("2024-01-01T11:00:00Z"), 100), // reset
        )
        val hours = StatisticsAggregator.hourAggregatesFromSamples(1L, samples, zone)
        assertEquals(2, hours.size)
        assertEquals(0L, hours[1].yieldWh)
    }

    @Test
    fun bucketsAcrossMidnight() {
        val samples = listOf(
            sample(epoch("2024-01-01T23:30:00Z"), 1000),
            sample(epoch("2024-01-02T00:30:00Z"), 1300),
        )
        val hours = StatisticsAggregator.hourAggregatesFromSamples(1L, samples, zone)
        assertEquals(2, hours.size)
        assertEquals(epoch("2024-01-01T23:00:00Z"), hours[0].hourEpochSeconds)
        assertEquals(epoch("2024-01-02T00:00:00Z"), hours[1].hourEpochSeconds)
        assertEquals(300L, hours[1].yieldWh)
    }

    @Test
    fun peakPowerIsMaxTotalPacInHour() {
        val samples = listOf(
            sample(epoch("2024-01-01T10:00:00Z"), 1000, power = 100),
            sample(epoch("2024-01-01T10:20:00Z"), 1100, power = 400),
            sample(epoch("2024-01-01T10:40:00Z"), 1200, power = 250),
        )
        val hours = StatisticsAggregator.hourAggregatesFromSamples(1L, samples, zone)
        assertEquals(1, hours.size)
        assertEquals(400, hours[0].maxPowerW)
    }

    @Test
    fun emptySamplesReturnEmpty() {
        assertTrue(StatisticsAggregator.hourAggregatesFromSamples(1L, emptyList(), zone).isEmpty())
    }

    private fun sample(epochSeconds: Long, eTotalWh: Long, power: Int? = null) =
        SpotSampleEntity(
            deviceId = 1L,
            timestampEpochSeconds = epochSeconds,
            eTotalWh = eTotalWh,
            totalPac = power,
        )

    private fun epoch(iso: String): Long =
        java.time.Instant.parse(iso).epochSecond
}
