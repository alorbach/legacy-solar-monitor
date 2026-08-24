package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.device.smaDayArchiveWindow
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SmaDayArchiveWindowTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun regularDayIncludesBaselineAndFullLocalDay() {
        val window = smaDayArchiveWindow(LocalDate.of(2026, 8, 21), berlin)

        assertEquals(
            LocalDate.of(2026, 8, 20).atTime(23, 55).atZone(berlin).toEpochSecond(),
            window.startEpochSeconds,
        )
        assertEquals(
            LocalDate.of(2026, 8, 21).atTime(23, 55).atZone(berlin).toEpochSecond(),
            window.endEpochSeconds,
        )
        assertEquals(Duration.ofHours(24).seconds, window.endEpochSeconds - window.startEpochSeconds)
    }

    @Test
    fun springForwardDayUsesNextLocalMidnight() {
        val window = smaDayArchiveWindow(LocalDate.of(2026, 3, 29), berlin)

        assertEquals(Duration.ofHours(23).seconds, window.endEpochSeconds - window.startEpochSeconds)
    }

    @Test
    fun fallBackDayUsesNextLocalMidnight() {
        val window = smaDayArchiveWindow(LocalDate.of(2026, 10, 25), berlin)

        assertEquals(Duration.ofHours(25).seconds, window.endEpochSeconds - window.startEpochSeconds)
    }
}
