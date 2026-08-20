package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.importing.ZipImportReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipImportReaderTest {
    @Test
    fun shouldParseFlattenedEntry_skipsNestedZip() {
        assertTrue(ZipImportReader.shouldParseFlattenedEntry("day.csv"))
        assertTrue(ZipImportReader.shouldParseFlattenedEntry("SBFspot.db"))
        assertFalse(ZipImportReader.shouldParseFlattenedEntry("inner.zip"))
        assertFalse(ZipImportReader.shouldParseFlattenedEntry("INNER.ZIP"))
    }

    @Test
    fun flatten_readsCsvEntries() {
        val bytes = zipOf("day.csv" to "a,b", "month.csv" to "c,d")
        val entries = ZipImportReader.flatten(bytes)
        assertEquals(listOf("day.csv", "month.csv"), entries.map { it.name })
    }

    @Test
    fun flatten_rejectsTooManyEntries() {
        val names = (0..ZipImportReader.MAX_ENTRY_COUNT).map { "e$it.csv" to "x" }
        try {
            ZipImportReader.flatten(zipOf(*names.toTypedArray()))
            throw AssertionError("expected entry count failure")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("entry count"))
        }
    }

    private fun zipOf(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
