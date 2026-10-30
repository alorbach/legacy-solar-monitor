package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.model.ArchiveSyncProgressEntity
import com.alorbach.solarmonitor.domain.ArchiveCatchUpPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveCatchUpPolicyTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-10-30T11:00:00Z")
    private val today = now.atZone(zone).toLocalDate()

    private fun progress(date: LocalDate, outcome: String = "saved", at: Instant = now) =
        ArchiveSyncProgressEntity(1, date.toEpochDay(), at.epochSecond, outcome)

    @Test fun firstRunPrioritizesTodayWithoutRelyingOnExistingSamples() {
        assertEquals(today, ArchiveCatchUpPolicy.nextDate(now, zone, emptyList()))
    }

    @Test fun todaySuccessfulReadIsThrottledForOneHour() {
        val records = listOf(progress(today))
        assertEquals(today.minusDays(30), ArchiveCatchUpPolicy.nextDate(now.plusSeconds(3599), zone, records))
        assertEquals(today, ArchiveCatchUpPolicy.nextDate(now.plusSeconds(3600), zone, records))
    }

    @Test fun unattemptedDaysAreOldestFirstRegardlessOfExistingSamples() {
        val records = listOf(progress(today), progress(today.minusDays(1)))
        assertEquals(today.minusDays(30), ArchiveCatchUpPolicy.nextDate(now, zone, records))
    }

    @Test fun reloadedProgressSkipsCompletedDaysUntilNextCalendarDay() {
        val records = (0L..30L).map { progress(today.minusDays(it)) }.map { it.copy() }
        assertNull(ArchiveCatchUpPolicy.nextDate(now, zone, records))
        val tomorrow = now.plusSeconds(86400)
        assertEquals(today.plusDays(1), ArchiveCatchUpPolicy.nextDate(tomorrow, zone, records))
    }

    @Test fun emptyHistoricalDayWaitsUntilNextLocalDay() {
        val records = (0L..30L).map { progress(today.minusDays(it), "empty") }
        assertNull(ArchiveCatchUpPolicy.nextDate(now.plusSeconds(3599), zone, records))
        val tomorrow = now.plusSeconds(86400)
        assertEquals(today, ArchiveCatchUpPolicy.nextDate(tomorrow, zone,
            records + progress(today.plusDays(1), at = tomorrow)))
    }

    @Test fun emptyTodayIsRetriedHourlyBecauseProductionCanStartLater() {
        val records = listOf(progress(today, "empty"))
        assertEquals(today.minusDays(30), ArchiveCatchUpPolicy.nextDate(now.plusSeconds(3599), zone, records))
        assertEquals(today, ArchiveCatchUpPolicy.nextDate(now.plusSeconds(3600), zone, records))
    }

    @Test fun failuresBackOffWithoutBlockingOtherDays() {
        val records = listOf(progress(today, "failed"))
        assertEquals(today.minusDays(30), ArchiveCatchUpPolicy.nextDate(now.plusSeconds(899), zone, records))
        assertEquals(today, ArchiveCatchUpPolicy.nextDate(now.plusSeconds(900), zone, records))
    }

    @Test fun exactlyThirtyPreviousDaysAreIncluded() {
        val records = (0L..29L).map { progress(today.minusDays(it)) }
        assertEquals(today.minusDays(30), ArchiveCatchUpPolicy.nextDate(now, zone, records))
        assertNull(ArchiveCatchUpPolicy.nextDate(now, zone, records + progress(today.minusDays(30))))
    }

    @Test fun localCalendarControlsRetryRatherThanUtcDate() {
        val lateUtc = Instant.parse("2026-10-30T23:30:00Z")
        val localToday = LocalDate.of(2026, 10, 31)
        assertEquals(localToday, ArchiveCatchUpPolicy.nextDate(lateUtc, zone, emptyList()))
    }

    @Test fun fallBackHourStillWaitsAnElapsedHour() {
        val firstHour = Instant.parse("2026-10-25T00:30:00Z")
        val secondHour = Instant.parse("2026-10-25T01:30:00Z")
        val date = firstHour.atZone(zone).toLocalDate()
        assertEquals(date, ArchiveCatchUpPolicy.nextDate(secondHour, zone,
            listOf(progress(date, at = firstHour))))
    }

    @Test fun springForwardDoesNotCauseEarlyHourlyRetry() {
        val before = Instant.parse("2026-03-29T00:45:00Z")
        val date = before.atZone(zone).toLocalDate()
        assertEquals(date.minusDays(30), ArchiveCatchUpPolicy.nextDate(before.plusSeconds(1800), zone,
            listOf(progress(date, at = before))))
    }

    @Test fun unattemptedDayWinsOverYesterdayDueForRecheck() {
        val records = listOf(progress(today), progress(today.minusDays(1), at = now.minusSeconds(86400)))
        assertEquals(today.minusDays(30), ArchiveCatchUpPolicy.nextDate(now, zone, records))
    }

    @Test fun attemptedHistoryIsRecheckedNewestFirstAndKeepsFailureBackoff() {
        val records = (0L..30L).map { offset ->
            progress(today.minusDays(offset), at = if (offset == 0L) now else now.minusSeconds(86400))
        }.toMutableList()
        records[1] = progress(today.minusDays(1), "failed")
        assertEquals(today.minusDays(2), ArchiveCatchUpPolicy.nextDate(now.plusSeconds(899), zone, records))
        assertEquals(today.minusDays(1), ArchiveCatchUpPolicy.nextDate(now.plusSeconds(900), zone, records))
    }
}
