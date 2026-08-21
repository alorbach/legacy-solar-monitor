package com.alorbach.solarmonitor.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Daily clock-hour window for live Bluetooth polling, evaluated in a device timezone. */
object LivePollWindow {
    const val DEFAULT_START_MINUTES = 6 * 60
    const val DEFAULT_END_MINUTES = 22 * 60
    private const val MAX_MINUTE = 23 * 60 + 59

    fun normalizeMinutes(minutes: Int): Int = minutes.coerceIn(0, MAX_MINUTE)

    /**
     * Open when [startMinutes] == [endMinutes] (24/7).
     * Otherwise half-open [start, end) on the local clock.
     * If start is after end, the window wraps overnight.
     */
    fun isOpen(
        now: Instant,
        startMinutes: Int,
        endMinutes: Int,
        zone: ZoneId,
    ): Boolean {
        val start = normalizeMinutes(startMinutes)
        val end = normalizeMinutes(endMinutes)
        if (start == end) return true
        val local = now.atZone(zone).toLocalTime()
        val startTime = localTime(start)
        val endTime = localTime(end)
        return if (start < end) {
            !local.isBefore(startTime) && local.isBefore(endTime)
        } else {
            !local.isBefore(startTime) || local.isBefore(endTime)
        }
    }

    fun millisUntilNextOpen(
        now: Instant,
        startMinutes: Int,
        endMinutes: Int,
        zone: ZoneId,
    ): Long {
        if (isOpen(now, startMinutes, endMinutes, zone)) return 0L
        val start = normalizeMinutes(startMinutes)
        val openAt = nextOpenInstant(now, start, zone)
        return (openAt.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0L)
    }

    /**
     * Milliseconds until the open window closes for [zone].
     * Returns [Long.MAX_VALUE] when the window is 24/7 or currently closed.
     */
    fun millisUntilClose(
        now: Instant,
        startMinutes: Int,
        endMinutes: Int,
        zone: ZoneId,
    ): Long {
        val start = normalizeMinutes(startMinutes)
        val end = normalizeMinutes(endMinutes)
        if (start == end) return Long.MAX_VALUE
        if (!isOpen(now, start, end, zone)) return Long.MAX_VALUE
        val closeAt = nextCloseInstant(now, end, zone)
        return (closeAt.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0L)
    }

    private fun nextOpenInstant(now: Instant, startMinutes: Int, zone: ZoneId): Instant {
        val local = now.atZone(zone)
        var day = local.toLocalDate()
        repeat(2) {
            val open = boundaryInstantOn(day, startMinutes, zone)
            if (open.isAfter(now)) return open
            day = day.plusDays(1)
        }
        return boundaryInstantOn(day, startMinutes, zone)
    }

    private fun nextCloseInstant(now: Instant, endMinutes: Int, zone: ZoneId): Instant {
        val local = now.atZone(zone)
        var day = local.toLocalDate()
        repeat(2) {
            val close = boundaryInstantOn(day, endMinutes, zone)
            if (close.isAfter(now)) return close
            day = day.plusDays(1)
        }
        return boundaryInstantOn(day, endMinutes, zone)
    }

    /**
     * Instant for a daily clock boundary on [day].
     * If that local time falls in a DST spring-forward gap, use the gap end
     * (first valid local time) so [isOpen] and delay/alarm math stay aligned.
     */
    private fun boundaryInstantOn(day: java.time.LocalDate, minutes: Int, zone: ZoneId): Instant {
        val localDateTime = java.time.LocalDateTime.of(day, localTime(minutes))
        val transition = zone.rules.getTransition(localDateTime)
        if (transition != null && transition.isGap) {
            return transition.instant
        }
        return localDateTime.atZone(zone).toInstant()
    }

    private fun localTime(minutes: Int): LocalTime =
        LocalTime.of(minutes / 60, minutes % 60)
}
