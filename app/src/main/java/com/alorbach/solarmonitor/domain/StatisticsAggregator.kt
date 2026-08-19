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
    internal const val MAX_POWER_GAP_SECONDS = 30 * 60L
    /** Lifetime ETotal jumps (empty CSV → 0, then a 100 MWh meter) are not hourly production. */
    internal const val MAX_PLAUSIBLE_HOUR_YIELD_WH = 50_000L

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
        var pendingPowerEstimateWh = 0L
        val estimatedIndices = mutableListOf<Int>()
        val hourStarts = byHour.keys.toList()
        val result = mutableListOf<HourAggregateEntity>()
        for ((index, hourStart) in hourStarts.withIndex()) {
            val hourSamples = byHour.getValue(hourStart)
            val totalsInHour = hourSamples.mapNotNull { usableTotalWh(it.eTotalWh) }
            val endTotal = totalsInHour.lastOrNull()
            val baseline = previousTotalWh ?: totalsInHour.firstOrNull()
            val previousSample = hourStarts.getOrNull(index - 1)?.let { byHour.getValue(it).lastOrNull() }
            val nextSample = hourStarts.getOrNull(index + 1)?.let { byHour.getValue(it).firstOrNull() }
            val hourEnd = Instant.ofEpochSecond(hourStart).atZone(zoneId).plusHours(1).toEpochSecond()
            var usedPowerEstimate = false
            val yield = when {
                endTotal == null || baseline == null -> {
                    val estimated = estimateYieldFromPower(
                        hourSamples, previousSample, nextSample, hourStart, hourEnd,
                    )
                    pendingPowerEstimateWh += estimated
                    usedPowerEstimate = true
                    estimated
                }
                endTotal > baseline -> {
                    val raw = endTotal - baseline
                    if (raw > MAX_PLAUSIBLE_HOUR_YIELD_WH) {
                        pendingPowerEstimateWh = 0L
                        estimatedIndices.clear()
                        0L
                    } else if (previousTotalWh == null) {
                        pendingPowerEstimateWh = 0L
                        estimatedIndices.clear()
                        raw
                    } else if (pendingPowerEstimateWh <= raw) {
                        val remainder = raw - pendingPowerEstimateWh
                        pendingPowerEstimateWh = 0L
                        estimatedIndices.clear()
                        remainder
                    } else {
                        reduceEstimatedHours(result, estimatedIndices, pendingPowerEstimateWh - raw)
                        pendingPowerEstimateWh = 0L
                        estimatedIndices.clear()
                        0L
                    }
                }
                endTotal == baseline -> {
                    val estimated = estimateYieldFromPower(
                        hourSamples, previousSample, nextSample, hourStart, hourEnd,
                    )
                    pendingPowerEstimateWh += estimated
                    usedPowerEstimate = true
                    estimated
                }
                else -> {
                    pendingPowerEstimateWh = 0L
                    estimatedIndices.clear()
                    0L // counter reset
                }
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
            if (usedPowerEstimate) estimatedIndices += result.lastIndex
        }
        return result
    }

    internal fun usableTotalWh(eTotalWh: Long?): Long? =
        eTotalWh?.takeIf { it > 0L }

    private fun reduceEstimatedHours(
        result: MutableList<HourAggregateEntity>,
        estimatedIndices: List<Int>,
        excessWh: Long,
    ) {
        var remaining = excessWh
        for (index in estimatedIndices.asReversed()) {
            if (remaining <= 0L) return
            val hour = result[index]
            val cut = minOf(hour.yieldWh, remaining)
            result[index] = hour.copy(yieldWh = hour.yieldWh - cut)
            remaining -= cut
        }
    }

    private fun estimateYieldFromPower(
        hourSamples: List<SpotSampleEntity>,
        previousSample: SpotSampleEntity?,
        nextSample: SpotSampleEntity?,
        hourStartEpochSeconds: Long,
        hourEndEpochSeconds: Long,
    ): Long {
        if (hourSamples.isEmpty()) return 0L
        var wattHours = 0.0
        val first = hourSamples.first()
        val leadWatts = previousSample?.totalPac
        if (leadWatts != null) {
            val gapSeconds = first.timestampEpochSeconds - previousSample.timestampEpochSeconds
            if (gapSeconds in 1..MAX_POWER_GAP_SECONDS) {
                val start = maxOf(hourStartEpochSeconds, previousSample.timestampEpochSeconds)
                val end = minOf(first.timestampEpochSeconds, hourEndEpochSeconds)
                if (end > start) {
                    wattHours += leadWatts * ((end - start) / 3600.0)
                }
            }
        }
        for (index in 1 until hourSamples.size) {
            val previous = hourSamples[index - 1]
            val current = hourSamples[index]
            val watts = previous.totalPac ?: continue
            val durationSeconds = current.timestampEpochSeconds - previous.timestampEpochSeconds
            if (durationSeconds in 1..MAX_POWER_GAP_SECONDS) {
                wattHours += watts * (durationSeconds / 3600.0)
            }
        }
        val last = hourSamples.last()
        val watts = last.totalPac ?: return wattHours.toLong()
        val adjacent = nextSample != null &&
            nextSample.timestampEpochSeconds - last.timestampEpochSeconds in 1..MAX_POWER_GAP_SECONDS
        val endEpoch = if (adjacent) {
            minOf(nextSample.timestampEpochSeconds, hourEndEpochSeconds)
        } else {
            last.timestampEpochSeconds
        }
        val tailSeconds = endEpoch - last.timestampEpochSeconds
        if (tailSeconds > 0) {
            wattHours += watts * (tailSeconds / 3600.0)
        }
        return wattHours.toLong()
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
