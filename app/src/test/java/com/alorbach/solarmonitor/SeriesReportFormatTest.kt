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
    fun mixedCurrencyExportLeavesEarningsBlankButRetainsMeasurements() {
        val csv = SeriesReportFormat.csvBody(
            listOf(StatsPoint("01", "1", 1500, 800, 123.45, eventCount = 2)),
            emptyList(),
            includeEarnings = false,
        )
        assertTrue(csv.contains("01,1,1500,800,,2"))
    }

    @Test
    fun csvEscapeQuotesCommasAndNewlines() {
        assertEquals("plain", SeriesReportFormat.csvEscape("plain"))
        assertEquals("\"a,b\"", SeriesReportFormat.csvEscape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", SeriesReportFormat.csvEscape("say \"hi\""))
        assertEquals("\"line\nbreak\"", SeriesReportFormat.csvEscape("line\nbreak"))
        assertEquals("\"line\rbreak\"", SeriesReportFormat.csvEscape("line\rbreak"))
    }

    @Test
    fun csvTextNeutralizesFormulaPrefixesAndWhitespaceBypasses() {
        listOf("=1+1", "+1+1", "-1+1", "@SUM(A1)", "  =1+1", "\uFEFF=1+1", "\u0000=1+1").forEach {
            assertEquals("'$it", SeriesReportFormat.csvEscape(it))
        }
        assertEquals("'\t=1+1", SeriesReportFormat.csvEscape("\t=1+1"))
        assertEquals("\"'\r=1+1\"", SeriesReportFormat.csvEscape("\r=1+1"))
        assertEquals("\"'\n=1+1\"", SeriesReportFormat.csvEscape("\n=1+1"))
        assertEquals("\"'=HYPERLINK(\"\"example\"\",\"\"label\"\")\"", SeriesReportFormat.csvEscape("=HYPERLINK(\"example\",\"label\")"))
    }

    @Test
    fun seriesCsvProtectsTextButKeepsNegativeNumbersNumeric() {
        val csv = SeriesReportFormat.csvBody(
            listOf(StatsPoint("=1+1", "@bucket", -1500, -800, -0.42, eventCount = 2)),
            listOf(
                DeviceEventEntity(
                    deviceId = 1, entryId = 9, timestampEpochSeconds = 0, eventCode = -33,
                    eventType = "Incoming", category = "+command", eventGroup = "Grid",
                    tag = "=1+1", oldValue = "-text", newValue = "@text", userGroup = null,
                ),
            ),
            ZoneOffset.UTC,
        )
        assertTrue(csv.contains("'=1+1,'@bucket,-1500,-800,-0.42,2"))
        assertTrue(csv.contains("-33,'+command,'=1+1,'-text,'@text"))
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
