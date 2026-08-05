package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.importing.SbfspotCsvParser
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SbfspotCsvParserTest {
    private val parser = SbfspotCsvParser(ZoneId.of("Europe/Berlin"))
    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "_legacy").exists() }
        ?: error("Unable to locate repo root from ${System.getProperty("user.dir")}")

    @Test
    fun parsesLegacyDayCsv() {
        val file = File(repoRoot, "_legacy/smadata/MeinePVAnlage-20120401.csv")
        val result = parser.parse(1L, file.name, file.inputStream())

        assertTrue(result.spotSamples.isNotEmpty())
        assertTrue(result.dayAggregates.isNotEmpty())
        assertEquals("MeinePVAnlage-20120401.csv", result.preservedName)
    }

    @Test
    fun parsesLegacyMonthCsv() {
        val file = File(repoRoot, "_legacy/smadata/MeinePVAnlage-201204.csv")
        val result = parser.parse(1L, file.name, file.inputStream())

        assertTrue(result.monthAggregates.isNotEmpty())
        assertEquals("2012-04", result.monthAggregates.first().monthKey)
    }

    @Test
    fun parsesLegacyEventCsv() {
        val file = File(repoRoot, "_legacy/smadata/Events/MeinePVAnlage-User-Events-202604-202604.csv")
        val result = parser.parse(1L, file.name, file.inputStream())

        assertTrue(result.events.isNotEmpty())
        assertTrue(result.events.any { it.eventCode == 10223 })
    }
}
