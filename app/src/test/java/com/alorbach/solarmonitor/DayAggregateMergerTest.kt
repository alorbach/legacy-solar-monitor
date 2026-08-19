package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.local.DayAggregateMerger
import com.alorbach.solarmonitor.data.model.DayAggregateEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class DayAggregateMergerTest {
    @Test
    fun zipMonthThenDayKeepsMonthCsvYield() {
        val month = day(yieldWh = 10_500L, source = "month_csv")
        val dayCsv = day(yieldWh = 1_000L, source = "day_csv", powerW = 400)
        val merged = DayAggregateMerger.coalesce(listOf(month, dayCsv)).single()
        assertEquals(10_500L, merged.totalYieldWh)
        assertEquals("month_csv", merged.sourceType)
        assertEquals(400, merged.powerW)
    }

    @Test
    fun zipDayThenMonthPrefersMonthCsvEvenIfLower() {
        val dayCsv = day(yieldWh = 40_000L, source = "day_csv")
        val month = day(yieldWh = 10_500L, source = "month_csv")
        val merged = DayAggregateMerger.coalesce(listOf(dayCsv, month)).single()
        assertEquals(10_500L, merged.totalYieldWh)
        assertEquals("month_csv", merged.sourceType)
    }

    @Test
    fun monthCsvReimportCanLowerStoredYield() {
        val prior = day(yieldWh = 40_000L, source = "day_csv")
        val incoming = day(yieldWh = 10_500L, source = "month_csv")
        val merged = DayAggregateMerger.merge(prior, incoming)
        assertEquals(10_500L, merged.totalYieldWh)
        assertEquals("month_csv", merged.sourceType)
    }

    @Test
    fun zeroIncomingDoesNotWipeExistingYield() {
        val prior = day(yieldWh = 10_500L, source = "month_csv")
        val incoming = day(yieldWh = 0L, source = "day_csv")
        val merged = DayAggregateMerger.merge(prior, incoming)
        assertEquals(10_500L, merged.totalYieldWh)
        assertEquals("month_csv", merged.sourceType)
    }

    @Test
    fun monthCsvZeroCorrectsPriorDayCsv() {
        val prior = day(yieldWh = 40_000L, source = "day_csv")
        val incoming = day(yieldWh = 0L, source = "month_csv")
        val merged = DayAggregateMerger.merge(prior, incoming)
        assertEquals(0L, merged.totalYieldWh)
        assertEquals("month_csv", merged.sourceType)
    }

    @Test
    fun bluetoothZeroDoesNotWipeMonthCsvYield() {
        val prior = day(yieldWh = 10_500L, source = "month_csv")
        val incoming = day(yieldWh = 0L, source = "bluetooth_day_archive")
        val merged = DayAggregateMerger.merge(prior, incoming)
        assertEquals(10_500L, merged.totalYieldWh)
        assertEquals("month_csv", merged.sourceType)
    }

    @Test
    fun sqliteZeroCorrectsMonthCsvYield() {
        val prior = day(yieldWh = 10_500L, source = "month_csv")
        val incoming = day(yieldWh = 0L, source = "sqlite")
        val merged = DayAggregateMerger.merge(prior, incoming)
        assertEquals(0L, merged.totalYieldWh)
        assertEquals("sqlite", merged.sourceType)
    }

    @Test
    fun sqliteArchiveCorrectsMonthCsv() {
        val prior = day(yieldWh = 10_500L, source = "month_csv")
        val incoming = day(yieldWh = 9_800L, source = "sqlite")
        val merged = DayAggregateMerger.merge(prior, incoming)
        assertEquals(9_800L, merged.totalYieldWh)
        assertEquals("sqlite", merged.sourceType)
    }

    @Test
    fun sameSourceReimportAllowsLowerCorrection() {
        val prior = day(yieldWh = 12_000L, source = "month_csv")
        val incoming = day(yieldWh = 10_000L, source = "month_csv")
        val merged = DayAggregateMerger.merge(prior, incoming)
        assertEquals(10_000L, merged.totalYieldWh)
    }

    @Test
    fun sqliteArchiveCanLowerPeakPower() {
        val prior = day(yieldWh = 10_500L, source = "month_csv", powerW = 900)
        val incoming = day(yieldWh = 9_800L, source = "sqlite", powerW = 400)
        val merged = DayAggregateMerger.merge(prior, incoming)
        assertEquals(400, merged.powerW)
    }

    private fun day(
        yieldWh: Long,
        source: String,
        powerW: Int? = null,
        dateEpochDay: Long = 20_000L,
    ) = DayAggregateEntity(
        deviceId = 1L,
        dateEpochDay = dateEpochDay,
        totalYieldWh = yieldWh,
        powerW = powerW,
        sourceType = source,
    )
}
