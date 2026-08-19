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

    @Test
    fun constantEnergyIntegratesGapAcrossHourBoundary() {
        val samples = listOf(
            sample(epoch("2024-01-01T10:55:00Z"), 1000, power = 3600),
            sample(epoch("2024-01-01T11:00:00Z"), 1000, power = 3600),
        )
        val hours = StatisticsAggregator.hourAggregatesFromSamples(1L, samples, zone)
        assertEquals(2, hours.size)
        assertEquals(300L, hours[0].yieldWh)
    }

    @Test
    fun constantEnergyFallsBackToPowerIntegral() {
        val samples = listOf(
            sample(epoch("2024-01-01T10:00:00Z"), 1000, power = 3600),
            sample(epoch("2024-01-01T10:30:00Z"), 1000, power = 3600),
        )
        val hours = StatisticsAggregator.hourAggregatesFromSamples(1L, samples, zone)
        assertEquals(1, hours.size)
        assertEquals(1800L, hours[0].yieldWh)
    }

    @Test
    fun powerEstimateIsNotAddedAgainWhenCounterResumes() {
        val samples = listOf(
            sample(epoch("2024-01-01T10:00:00Z"), 1000, power = 3600),
            sample(epoch("2024-01-01T10:30:00Z"), 1000, power = 3600),
            sample(epoch("2024-01-01T11:00:00Z"), 4000, power = 3600),
        )
        val hours = StatisticsAggregator.hourAggregatesFromSamples(1L, samples, zone)
        assertEquals(2, hours.size)
        assertEquals(3000L, hours[0].yieldWh)
        assertEquals(0L, hours[1].yieldWh)
    }

    @Test
    fun distantNextSampleDoesNotFillRestOfHour() {
        val samples = listOf(
            sample(epoch("2024-01-01T10:00:00Z"), 1000, power = 3600),
            sample(epoch("2024-01-02T10:00:00Z"), 1000, power = 3600),
        )
        val hours = StatisticsAggregator.hourAggregatesFromSamples(1L, samples, zone)
        assertEquals(0L, hours[0].yieldWh)
    }

    @Test
    fun intraHourTelemetryOutageIsNotFilledByPower() {
        val samples = listOf(
            sample(epoch("2024-01-01T10:00:00Z"), 1000, power = 3600),
            sample(epoch("2024-01-01T10:55:00Z"), 1000, power = 3600),
        )
        val hours = StatisticsAggregator.hourAggregatesFromSamples(1L, samples, zone)
        assertEquals(1, hours.size)
        assertEquals(0L, hours[0].yieldWh)
    }

    @Test
    fun crossHourAdjacentSamplesSplitAtBoundary() {
        val samples = listOf(
            sample(epoch("2024-01-01T10:50:00Z"), 1000, power = 3600),
            sample(epoch("2024-01-01T11:10:00Z"), 1000, power = 3600),
        )
        val hours = StatisticsAggregator.hourAggregatesFromSamples(1L, samples, zone)
        assertEquals(2, hours.size)
        assertEquals(600L, hours[0].yieldWh)
        assertEquals(600L, hours[1].yieldWh)
    }

    @Test
    fun zeroThenLifetimeTotalIsNotHourlyYield() {
        val samples = listOf(
            sample(epoch("2024-01-01T11:00:00Z"), 0),
            sample(epoch("2024-01-01T12:00:00Z"), 101_636_000L),
            sample(epoch("2024-01-01T12:30:00Z"), 101_638_000L),
        )
        val hours = StatisticsAggregator.hourAggregatesFromSamples(1L, samples, zone)
        val noon = hours.single { java.time.Instant.ofEpochSecond(it.hourEpochSeconds).atZone(zone).hour == 12 }
        assertEquals(2_000L, noon.yieldWh)
        assertTrue(hours.none { it.yieldWh > StatisticsAggregator.MAX_PLAUSIBLE_HOUR_YIELD_WH })
    }

    @Test
    fun implausibleJumpIsDroppedNotStoredAsYield() {
        val samples = listOf(
            sample(epoch("2024-01-01T10:00:00Z"), 1_000),
            sample(epoch("2024-01-01T11:00:00Z"), 101_636_000L),
        )
        val hours = StatisticsAggregator.hourAggregatesFromSamples(1L, samples, zone)
        assertEquals(0L, hours[1].yieldWh)
    }

    @Test
    fun risingDayCsvTotalsStayHourScale() {
        val samples = listOf(
            sample(epoch("2024-01-01T10:00:00Z"), 29_933),
            sample(epoch("2024-01-01T10:30:00Z"), 31_000),
            sample(epoch("2024-01-01T11:00:00Z"), 32_200),
        )
        val hours = StatisticsAggregator.hourAggregatesFromSamples(1L, samples, zone)
        assertEquals(1_067L, hours[0].yieldWh)
        assertEquals(1_200L, hours[1].yieldWh)
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
