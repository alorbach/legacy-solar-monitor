package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.model.DailyPoint
import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import com.alorbach.solarmonitor.data.model.StatsPoint
import com.alorbach.solarmonitor.domain.SeriesReportFormat
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesReportFormatTest {
    @Test
    fun csvEscapeQuotesCommasAndNewlines() {
        assertEquals("plain", SeriesReportFormat.csvEscape("plain"))
        assertEquals("\"a,b\"", SeriesReportFormat.csvEscape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", SeriesReportFormat.csvEscape("say \"hi\""))
        assertEquals("\"line\nbreak\"", SeriesReportFormat.csvEscape("line\nbreak"))
    }

    @Test
    fun csvBodyIncludesPointsAndEvents() {
        val csv = SeriesReportFormat.csvBody(
            points = listOf(
                StatsPoint("01", "1", 1500, 800, 0.42, eventCount = 2),
            ),
            events = listOf(
                DeviceEventEntity(
                    id = 1,
                    deviceId = 1,
                    entryId = 9,
                    timestampEpochSeconds = 0,
                    eventCode = 33,
                    eventType = "Incoming",
                    category = "Warning",
                    eventGroup = "Grid",
                    tag = "unstable",
                    oldValue = "0",
                    newValue = "1",
                    userGroup = null,
                ),
            ),
            eventZone = ZoneOffset.UTC,
        )
        assertTrue(csv.startsWith("label,bucket,yieldWh,peakW,earnings,eventCount"))
        assertTrue(csv.contains("01,1,1500,800,0.42,2"))
        assertTrue(csv.contains("timestamp,code,category,tag,old,new"))
        assertTrue(csv.contains("33,Warning,unstable,0,1"))
    }

    @Test
    fun emptySeriesStillWritesHeader() {
        val csv = SeriesReportFormat.csvBody(emptyList(), emptyList())
        assertEquals("label,bucket,yieldWh,peakW,earnings,eventCount\n", csv)
    }

    @Test
    fun dailyPointsMapToStatsRows() {
        val rows = SeriesReportFormat.dailyPointsToStats(listOf(DailyPoint(1, 1000, 0.5)))
        assertEquals(1, rows.size)
        assertEquals("1", rows[0].bucketKey)
        assertEquals(1000L, rows[0].yieldWh)
    }

    @Test
    fun pdfRowCapIsForty() {
        assertEquals(40, SeriesReportFormat.MAX_PDF_ROWS)
    }
}
