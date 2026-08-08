package com.alorbach.solarmonitor.domain

import com.alorbach.solarmonitor.data.model.HourAggregateEntity
import com.alorbach.solarmonitor.data.model.SpotSampleEntity
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Builds hour aggregates from ordered spot samples.
 *
 * Yield for an hour is the delta of the cumulative `eTotalWh` counter from the last
 * reading before (or at) the hour start to the last reading inside the hour.
 * When no prior-hour baseline exists, the first reading inside the hour is used as
 * the baseline so intra-hour production is not dropped.
 * Counter resets (negative deltas) are treated as zero for that step.
 */
object StatisticsAggregator {
    fun hourAggregatesFromSamples(
        deviceId: Long,
        samples: List<SpotSampleEntity>,
        zoneId: ZoneId,
        sourceType: String = "derived",
    ): List<HourAggregateEntity> {
        if (samples.isEmpty()) return emptyList()

        val ordered = samples.sortedBy { it.timestampEpochSeconds }
        val byHour = linkedMapOf<Long, MutableList<SpotSampleEntity>>()
        for (sample in ordered) {
            val hourStart = hourStartEpochSeconds(sample.timestampEpochSeconds, zoneId)
            byHour.getOrPut(hourStart) { mutableListOf() }.add(sample)
        }

        var previousTotalWh: Long? = null
        val result = mutableListOf<HourAggregateEntity>()
        for ((hourStart, hourSamples) in byHour) {
            val totalsInHour = hourSamples.mapNotNull { it.eTotalWh }
            val endTotal = totalsInHour.lastOrNull()
            val baseline = previousTotalWh ?: totalsInHour.firstOrNull()
            val yield = when {
                endTotal == null || baseline == null -> 0L
                endTotal >= baseline -> endTotal - baseline
                else -> 0L // counter reset
            }
            val maxPower = hourSamples.mapNotNull { it.totalPac }.maxOrNull()
            if (endTotal != null) {
                previousTotalWh = endTotal
            }
            result += HourAggregateEntity(
                deviceId = deviceId,
                hourEpochSeconds = hourStart,
                yieldWh = yield,
                maxPowerW = maxPower,
                sourceType = sourceType,
            )
        }
        return result
    }

    fun hourStartEpochSeconds(epochSeconds: Long, zoneId: ZoneId): Long {
        val zoned = Instant.ofEpochSecond(epochSeconds).atZone(zoneId)
        return ZonedDateTime.of(
            zoned.year,
            zoned.monthValue,
            zoned.dayOfMonth,
            zoned.hour,
            0,
            0,
            0,
            zoneId,
        ).toEpochSecond()
    }
}
