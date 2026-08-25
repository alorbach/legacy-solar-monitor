package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.importing.CsvParseOptions
import com.alorbach.solarmonitor.data.importing.SbfspotCsvParser
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SbfspotCsvParserTest {
    private val parser = SbfspotCsvParser(ZoneId.of("Europe/Berlin"))
    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Unable to locate repo root from ${System.getProperty("user.dir")}")

    @Test
    fun parsesLegacyDayCsv() {
        val file = File(repoRoot, "_legacy/smadata/MeinePVAnlage-20120401.csv")
        assumeTrue("legacy day CSV fixture is not present", file.isFile)
        val result = parser.parse(1L, file.name, file.inputStream())

        assertTrue(result.spotSamples.isNotEmpty())
        assertTrue(result.dayAggregates.isNotEmpty())
        assertEquals("MeinePVAnlage-20120401.csv", result.preservedName)
        // Day CSV column is cumulative meter reading; daily yield is end - start
        // 69.228 kWh - 29.933 kWh = 39.295 kWh
        assertEquals(39_295L, result.dayAggregates.first().totalYieldWh)
    }

    @Test
    fun parsesLegacyMonthCsv() {
        val file = File(repoRoot, "_legacy/smadata/MeinePVAnlage-201204.csv")
        assumeTrue("legacy month CSV fixture is not present", file.isFile)
        val result = parser.parse(1L, file.name, file.inputStream())

        assertTrue(result.monthAggregates.isNotEmpty())
        assertEquals(1, result.monthAggregates.size)
        assertEquals("2012-04", result.monthAggregates.first().monthKey)
        // Latest cumulative total for April and sum of daily day-yield column
        assertEquals(691_737L, result.monthAggregates.first().totalYieldWh)
        assertTrue(result.monthAggregates.first().dayYieldWh > 600_000L)
        assertTrue(result.dayAggregates.isNotEmpty())
    }

    @Test
    fun parsesLegacyEventCsv() {
        val file = File(repoRoot, "_legacy/smadata/Events/MeinePVAnlage-User-Events-202604-202604.csv")
        assumeTrue("legacy event CSV fixture is not present", file.isFile)
        val result = parser.parse(1L, file.name, file.inputStream())

        assertTrue(result.events.isNotEmpty())
        assertTrue(result.events.any { it.eventCode == 10223 })
    }

    @Test
    fun monthCsvEmitsPerDayYields() {
        val csv = """
            Version CSV1.0;;;;;;Decimalpoint comma;Delimiter semicolon
            dd.MM.yyyy;kWh;kWh
            01.07.2026;5000,000;10,500
            02.07.2026;5012,250;12,250
            03.07.2026;5020,000;7,750
        """.trimIndent()
        val result = parser.parse(1L, "Plant-202607.csv", csv.byteInputStream())

        assertEquals(1, result.monthAggregates.size)
        assertEquals("2026-07", result.monthAggregates.first().monthKey)
        assertEquals(30_500L, result.monthAggregates.first().dayYieldWh)
        assertEquals(3, result.dayAggregates.size)
        val byDay = result.dayAggregates.associateBy { it.dateEpochDay }
        assertEquals(10_500L, byDay[LocalDate.of(2026, 7, 1).toEpochDay()]?.totalYieldWh)
        assertEquals(12_250L, byDay[LocalDate.of(2026, 7, 2).toEpochDay()]?.totalYieldWh)
        assertEquals(7_750L, byDay[LocalDate.of(2026, 7, 3).toEpochDay()]?.totalYieldWh)
        assertEquals("month_csv", result.dayAggregates.first().sourceType)
    }

    @Test
    fun dayCsvCumulativeMeterUsesLastMinusFirst() {
        val csv = """
            Version CSV1.0;;;;;;Decimalpoint comma;Delimiter semicolon
            dd.MM.yyyy HH:mm;kWh;kW
            01.04.2012 06:00;29,933;0,000
            01.04.2012 12:00;50,000;3,200
            01.04.2012 18:00;69,228;0,100
        """.trimIndent()
        val result = parser.parse(1L, "Plant-20120401.csv", csv.byteInputStream())

        assertEquals(3, result.spotSamples.size)
        assertEquals(1, result.dayAggregates.size)
        assertEquals(39_295L, result.dayAggregates.first().totalYieldWh)
        assertEquals(3_200, result.dayAggregates.first().powerW)
        assertEquals(29_933L, result.spotSamples.first().eTotalWh)
    }

    @Test
    fun dayCsvBlankEnergyIsNullNotZero() {
        val csv = """
            Version CSV1.0;;;;;;Decimalpoint comma;Delimiter semicolon
            dd.MM.yyyy HH:mm;kWh;kW
            01.07.2026 11:00;;0,000
            01.07.2026 12:00;101636,123;4,800
            01.07.2026 13:00;101638,000;3,200
        """.trimIndent()
        val result = parser.parse(1L, "Plant-20260701.csv", csv.byteInputStream())
        assertEquals(null, result.spotSamples[0].eTotalWh)
        assertEquals(101_636_123L, result.spotSamples[1].eTotalWh)
        assertEquals(1, result.dayAggregates.size)
        assertEquals(1_877L, result.dayAggregates.first().totalYieldWh)
    }

    @Test
    fun whitespaceCommaOptionStillParsesGermanKwh() {
        val csv = """
            Version CSV1.0
            dd.MM.yyyy;kWh;kWh
            01.07.2026;5000,000;10,500
        """.trimIndent()
        val spaced = SbfspotCsvParser(
            CsvParseOptions(zoneId = ZoneId.of("Europe/Berlin"), decimalPoint = "comma "),
        )
        val result = spaced.parse(1L, "Plant-202607.csv", csv.byteInputStream())
        assertEquals(10_500L, result.dayAggregates.single().totalYieldWh)
    }

    @Test
    fun monthCsvSkipsBlankDayYieldRows() {
        val csv = """
            Version CSV1.0;;;;;;Decimalpoint comma;Delimiter semicolon
            dd.MM.yyyy;kWh;kWh
            01.07.2026;5000,000;10,500
            02.07.2026;5000,000;
        """.trimIndent()
        val result = parser.parse(1L, "Plant-202607.csv", csv.byteInputStream())
        assertEquals(1, result.dayAggregates.size)
        assertEquals(10_500L, result.dayAggregates.single().totalYieldWh)
        assertEquals(5_000_000L, result.monthAggregates.single().totalYieldWh)
    }

    @Test
    fun dayCsvConstantEnergyDoesNotEmitZeroDay() {
        val csv = """
            Version CSV1.0;;;;;;Decimalpoint comma;Delimiter semicolon
            dd.MM.yyyy HH:mm;kWh;kW
            01.07.2026 06:00;10,500;0,000
            01.07.2026 12:00;10,500;4,000
            01.07.2026 18:00;10,500;0,200
        """.trimIndent()
        val result = parser.parse(1L, "Plant-20260701.csv", csv.byteInputStream())

        assertEquals(3, result.spotSamples.size)
        assertTrue(result.dayAggregates.isEmpty())
    }

    private fun String.byteInputStream(): ByteArrayInputStream =
        ByteArrayInputStream(toByteArray(Charsets.UTF_8))
}
