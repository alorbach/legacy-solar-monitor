package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.domain.LivePollWindow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePollWindowTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun defaultWindowClosedBeforeSix() {
        val now = instant(2026, 8, 21, 5, 59)
        assertFalse(LivePollWindow.isOpen(now, 6 * 60, 22 * 60, berlin))
    }

    @Test
    fun defaultWindowOpensAtSix() {
        val now = instant(2026, 8, 21, 6, 0)
        assertTrue(LivePollWindow.isOpen(now, 6 * 60, 22 * 60, berlin))
    }

    @Test
    fun defaultWindowOpenJustBeforeEnd() {
        val now = instant(2026, 8, 21, 21, 59)
        assertTrue(LivePollWindow.isOpen(now, 6 * 60, 22 * 60, berlin))
    }

    @Test
    fun defaultWindowClosedAtEnd() {
        val now = instant(2026, 8, 21, 22, 0)
        assertFalse(LivePollWindow.isOpen(now, 6 * 60, 22 * 60, berlin))
    }

    @Test
    fun equalStartAndEndIsAlwaysOpen() {
        val night = instant(2026, 8, 21, 3, 0)
        assertTrue(LivePollWindow.isOpen(night, 8 * 60, 8 * 60, berlin))
        assertEquals(0L, LivePollWindow.millisUntilNextOpen(night, 8 * 60, 8 * 60, berlin))
    }

    @Test
    fun overnightWrapOpensAtNight() {
        val start = 22 * 60
        val end = 6 * 60
        assertTrue(LivePollWindow.isOpen(instant(2026, 8, 21, 22, 0), start, end, berlin))
        assertTrue(LivePollWindow.isOpen(instant(2026, 8, 21, 3, 0), start, end, berlin))
        assertFalse(LivePollWindow.isOpen(instant(2026, 8, 21, 6, 0), start, end, berlin))
        assertFalse(LivePollWindow.isOpen(instant(2026, 8, 21, 12, 0), start, end, berlin))
    }

    @Test
    fun millisUntilNextOpenUsesDeviceTimezone() {
        val now = instant(2026, 8, 21, 22, 30)
        val wait = LivePollWindow.millisUntilNextOpen(now, 6 * 60, 22 * 60, berlin)
        val newYork = ZoneId.of("America/New_York")
        val waitNy = LivePollWindow.millisUntilNextOpen(now, 6 * 60, 22 * 60, newYork)
        assertTrue(wait > 0L)
        assertTrue(waitNy != wait)
        val reopen = now.plusMillis(wait).atZone(berlin)
        assertEquals(6, reopen.hour)
        assertEquals(0, reopen.minute)
    }

    @Test
    fun millisUntilCloseRespectsEndBoundary() {
        val now = instant(2026, 8, 21, 21, 30)
        val wait = LivePollWindow.millisUntilClose(now, 6 * 60, 22 * 60, berlin)
        assertEquals(30L * 60L * 1000L, wait)
        assertEquals(Long.MAX_VALUE, LivePollWindow.millisUntilClose(now, 8 * 60, 8 * 60, berlin))
        assertEquals(
            Long.MAX_VALUE,
            LivePollWindow.millisUntilClose(instant(2026, 8, 21, 23, 0), 6 * 60, 22 * 60, berlin),
        )
    }

    @Test
    fun millisUntilNextOpenUsesGapEndOnSpringForward() {
        // Europe/Berlin 2026-03-29: clocks jump 02:00 -> 03:00.
        val beforeGap = LocalDateTime.of(2026, 3, 29, 1, 30).atZone(berlin).toInstant()
        val wait = LivePollWindow.millisUntilNextOpen(beforeGap, 2 * 60 + 30, 22 * 60, berlin)
        val openAt = beforeGap.plusMillis(wait).atZone(berlin)
        assertEquals(3, openAt.hour)
        assertEquals(0, openAt.minute)
        assertTrue(LivePollWindow.isOpen(beforeGap.plusMillis(wait), 2 * 60 + 30, 22 * 60, berlin))
    }

    private fun instant(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant =
        LocalDateTime.of(year, month, day, hour, minute).atZone(berlin).toInstant()
}
