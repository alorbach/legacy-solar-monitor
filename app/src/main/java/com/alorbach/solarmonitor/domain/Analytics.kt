package com.alorbach.solarmonitor.domain

import com.alorbach.solarmonitor.data.model.DayAggregateEntity
import com.alorbach.solarmonitor.data.model.MonthAggregateEntity
import com.alorbach.solarmonitor.data.model.TariffPeriodEntity
import java.time.LocalDate
import java.time.YearMonth

object EarningsCalculator {
    fun earningsForDay(day: DayAggregateEntity, tariffs: List<TariffPeriodEntity>): Double {
        val date = LocalDate.ofEpochDay(day.dateEpochDay)
        val tariff = matchingTariff(tariffs, date.toEpochDay(), date.toEpochDay()) ?: return 0.0
        return (day.totalYieldWh / 1000.0) * tariff.pricePerKwh
    }

    fun earningsForMonth(month: MonthAggregateEntity, tariffs: List<TariffPeriodEntity>): Double {
        val yearMonth = YearMonth.parse(month.monthKey)
        val start = yearMonth.atDay(1).toEpochDay()
        val end = yearMonth.atEndOfMonth().toEpochDay()
        val matching = matchingTariff(tariffs, start, end) ?: return 0.0
        // dayYieldWh holds the month production total after the import fix
        return (month.dayYieldWh / 1000.0) * matching.pricePerKwh
    }

    private fun matchingTariff(
        tariffs: List<TariffPeriodEntity>,
        rangeStart: Long,
        rangeEnd: Long,
    ): TariffPeriodEntity? {
        return tariffs
            .filter {
                val afterStart = rangeEnd >= it.validFromEpochDay
                val beforeEnd = it.validToEpochDay?.let { validTo -> rangeStart <= validTo } ?: true
                afterStart && beforeEnd
            }
            .maxByOrNull { it.validFromEpochDay }
    }
}

object YieldFormatting {
    fun whToKwhLabel(wh: Long?): String =
        if (wh == null) "--"
        else String.format("%.3f kWh", wh / 1000.0)

    fun wattsLabel(watts: Int?): String =
        if (watts == null) "--"
        else "$watts W"
}
