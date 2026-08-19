package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.model.DayAggregateEntity
import com.alorbach.solarmonitor.data.model.MonthAggregateEntity
import com.alorbach.solarmonitor.data.model.TariffPeriodEntity
import com.alorbach.solarmonitor.domain.EarningsCalculator
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EarningsCalculatorTest {
    @Test
    fun usesMatchingTariffPeriodForDay() {
        val day = DayAggregateEntity(
            deviceId = 1,
            dateEpochDay = LocalDate.of(2020, 5, 10).toEpochDay(),
            totalYieldWh = 12_500,
        )
        val tariffs = listOf(
            TariffPeriodEntity(deviceId = 1, validFromEpochDay = LocalDate.of(2010, 1, 1).toEpochDay(), validToEpochDay = LocalDate.of(2019, 12, 31).toEpochDay(), pricePerKwh = 0.43, currency = "EUR"),
            TariffPeriodEntity(deviceId = 1, validFromEpochDay = LocalDate.of(2020, 1, 1).toEpochDay(), validToEpochDay = null, pricePerKwh = 0.21, currency = "EUR"),
        )

        val earnings = EarningsCalculator.earningsForDay(day, tariffs)

        assertEquals(2.625, earnings, 0.0001)
    }

    @Test
    fun usesMatchingTariffPeriodForMonth() {
        val month = MonthAggregateEntity(
            deviceId = 1,
            monthKey = "2024-04",
            totalYieldWh = 50_000,
            dayYieldWh = 50_000,
        )
        val tariffs = listOf(
            TariffPeriodEntity(deviceId = 1, validFromEpochDay = LocalDate.of(2024, 1, 1).toEpochDay(), validToEpochDay = null, pricePerKwh = 0.30, currency = "EUR"),
        )

        val earnings = EarningsCalculator.earningsForMonth(month, tariffs)

        assertEquals(15.0, earnings, 0.0001)
    }
}

class YieldFormattingTest {
    @Test
    fun earningsLabelIncludesEuroSymbol() {
        val label = com.alorbach.solarmonitor.domain.YieldFormatting.earningsLabel(
            124.67,
            "EUR",
            java.util.Locale.GERMANY,
        )
        assertTrue(label.contains("124,67"))
        assertTrue(label.contains("€") || label.contains("EUR"))
    }
}

class StatsSeriesFillTest {
    @Test
    fun currentMonthStopsAtToday() {
        val today = LocalDate.of(2026, 8, 18)
        val last = com.alorbach.solarmonitor.domain.StatsSeriesFill.lastInclusiveEpochDay(
            java.time.YearMonth.of(2026, 8),
            today,
        )
        assertEquals(today.toEpochDay(), last)
    }

    @Test
    fun pastMonthFillsThroughMonthEnd() {
        val today = LocalDate.of(2026, 8, 18)
        val last = com.alorbach.solarmonitor.domain.StatsSeriesFill.lastInclusiveEpochDay(
            java.time.YearMonth.of(2026, 4),
            today,
        )
        assertEquals(LocalDate.of(2026, 4, 30).toEpochDay(), last)
    }
}
