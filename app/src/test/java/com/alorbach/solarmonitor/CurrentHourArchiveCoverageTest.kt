package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.domain.CurrentHourArchiveCoverage
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentHourArchiveCoverageTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun archiveSampleInCurrentLocalHourIsCovered() {
        val now = epoch("2026-08-22T14:45:00")
        val latest = epoch("2026-08-22T14:05:00")

        assertTrue(CurrentHourArchiveCoverage.isCovered(latest, now, berlin))
    }

    @Test
    fun archiveSampleFromPreviousHourNeedsRefresh() {
        val now = epoch("2026-08-22T14:00:00")
        val latest = epoch("2026-08-22T13:59:59")

        assertFalse(CurrentHourArchiveCoverage.isCovered(latest, now, berlin))
    }

    @Test
    fun futureArchiveSampleDoesNotCountAsCoverage() {
        val now = epoch("2026-08-22T14:00:00")
        val latest = epoch("2026-08-22T14:01:00")

        assertFalse(CurrentHourArchiveCoverage.isCovered(latest, now, berlin))
    }

    @Test
    fun coverageUsesLocalHourAcrossSpringForward() {
        val now = epoch("2026-03-29T03:45:00")
        val latest = epoch("2026-03-29T03:05:00")

        assertTrue(CurrentHourArchiveCoverage.isCovered(latest, now, berlin))
    }

    @Test
    fun earlierFallBackHourDoesNotCoverLaterFallBackHour() {
        val latest = ZonedDateTime.of(
            LocalDateTime.of(2026, 10, 25, 2, 55),
            berlin,
        ).withEarlierOffsetAtOverlap().toEpochSecond()
        val now = ZonedDateTime.of(
            LocalDateTime.of(2026, 10, 25, 2, 5),
            berlin,
        ).withLaterOffsetAtOverlap().toEpochSecond()

        assertFalse(CurrentHourArchiveCoverage.isCovered(latest, now, berlin))
    }

    private fun epoch(value: String): Long =
        LocalDateTime.parse(value).atZone(berlin).toEpochSecond()
}
