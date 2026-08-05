package com.alorbach.solarmonitor.domain

import com.alorbach.solarmonitor.data.model.DayAggregateEntity
import com.alorbach.solarmonitor.data.model.MonthAggregateEntity
import com.alorbach.solarmonitor.data.model.TariffPeriodEntity
import java.time.LocalDate
import java.time.YearMonth

object EarningsCalculator {
    fun earningsForDay(day: DayAggregateEntity, tariffs: List<TariffPeriodEntity>): Double {
        val date = LocalDate.ofEpochDay(day.dateEpochDay)
        val tariff = tariffs.firstOrNull {
            val starts = date.toEpochDay() >= it.validFromEpochDay
            val ends = it.validToEpochDay?.let { end -> date.toEpochDay() <= end } ?: true
            starts && ends
        } ?: return 0.0
        return (day.totalYieldWh / 1000.0) * tariff.pricePerKwh
    }

    fun earningsForMonth(month: MonthAggregateEntity, tariffs: List<TariffPeriodEntity>): Double {
        val yearMonth = YearMonth.parse(month.monthKey)
        val matching = tariffs.firstOrNull {
            val start = yearMonth.atDay(1).toEpochDay()
            val end = yearMonth.atEndOfMonth().toEpochDay()
            val afterStart = end >= it.validFromEpochDay
            val beforeEnd = it.validToEpochDay?.let { validTo -> start <= validTo } ?: true
            afterStart && beforeEnd
        } ?: return 0.0
        return (month.dayYieldWh / 1000.0) * matching.pricePerKwh
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
