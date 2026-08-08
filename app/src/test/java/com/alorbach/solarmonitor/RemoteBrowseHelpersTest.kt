package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.importing.RemoteBrowseHelpers
import com.alorbach.solarmonitor.data.importing.RemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBrowseHelpersTest {
    @Test
    fun isImportableFile_acceptsSupportedExtensions() {
        assertTrue(RemoteBrowseHelpers.isImportableFile("day.csv"))
        assertTrue(RemoteBrowseHelpers.isImportableFile("archive.ZIP"))
        assertTrue(RemoteBrowseHelpers.isImportableFile("SBFspot.db"))
        assertFalse(RemoteBrowseHelpers.isImportableFile("readme.txt"))
        assertFalse(RemoteBrowseHelpers.isImportableFile("folder"))
    }

    @Test
    fun normalizeDirectory_and_joinPath() {
        assertEquals("/", RemoteBrowseHelpers.normalizeDirectory(""))
        assertEquals("/", RemoteBrowseHelpers.normalizeDirectory("/"))
        assertEquals("/smadata", RemoteBrowseHelpers.normalizeDirectory("smadata/"))
        assertEquals("/a/b", RemoteBrowseHelpers.normalizeDirectory("\\a\\b\\"))

        assertEquals("/smadata/day.csv", RemoteBrowseHelpers.joinPath("/smadata", "day.csv"))
        assertEquals("/day.csv", RemoteBrowseHelpers.joinPath("/", "day.csv"))
        assertEquals("/", RemoteBrowseHelpers.joinPath("/smadata", ".."))
        assertEquals("/smadata", RemoteBrowseHelpers.joinPath("/smadata/events", ".."))
    }

    @Test
    fun parentPath_stopsAtRoot() {
        assertNull(RemoteBrowseHelpers.parentPath("/"))
        assertEquals("/", RemoteBrowseHelpers.parentPath("/smadata"))
        assertEquals("/smadata", RemoteBrowseHelpers.parentPath("/smadata/events"))
    }

    @Test
    fun fileName_usesLastSegment() {
        assertEquals("day.csv", RemoteBrowseHelpers.fileName("/smadata/day.csv"))
        assertEquals("archive.zip", RemoteBrowseHelpers.fileName("archive.zip"))
    }

    @Test
    fun prepareBrowseEntries_filtersAndSorts() {
        val prepared = RemoteBrowseHelpers.prepareBrowseEntries(
            listOf(
                RemoteEntry("notes.txt", "/notes.txt", isDirectory = false, size = 10),
                RemoteEntry("zeta.csv", "/zeta.csv", isDirectory = false, size = 20),
                RemoteEntry("alpha", "/alpha", isDirectory = true),
                RemoteEntry("beta.zip", "/beta.zip", isDirectory = false, size = 30),
                RemoteEntry("Gamma", "/Gamma", isDirectory = true),
            ),
        )

        assertEquals(
            listOf("alpha", "Gamma", "beta.zip", "zeta.csv"),
            prepared.map { it.name },
        )
        assertTrue(prepared.none { it.name == "notes.txt" })
    }

    @Test
    fun looksLikeDirectory_detectsFolders() {
        assertTrue(RemoteBrowseHelpers.looksLikeDirectory("/smadata"))
        assertTrue(RemoteBrowseHelpers.looksLikeDirectory("/smadata/"))
        assertFalse(RemoteBrowseHelpers.looksLikeDirectory("/smadata/day.csv"))
    }

    @Test
    fun collectCsvFiles_walksRecursively() {
        val tree = mapOf(
            "/" to listOf(
                RemoteEntry("smadata", "/smadata", isDirectory = true),
                RemoteEntry("readme.txt", "/readme.txt", isDirectory = false),
            ),
            "/smadata" to listOf(
                RemoteEntry("Events", "/smadata/Events", isDirectory = true),
                RemoteEntry("day.csv", "/smadata/day.csv", isDirectory = false),
                RemoteEntry("month.csv", "/smadata/month.csv", isDirectory = false),
            ),
            "/smadata/Events" to listOf(
                RemoteEntry("events.csv", "/smadata/Events/events.csv", isDirectory = false),
                RemoteEntry("notes.log", "/smadata/Events/notes.log", isDirectory = false),
            ),
        )
        val csv = RemoteBrowseHelpers.collectCsvFiles("/") { dir ->
            tree[dir].orEmpty()
        }
        assertEquals(
            listOf("/smadata/day.csv", "/smadata/Events/events.csv", "/smadata/month.csv"),
            csv.map { it.path },
        )
    }

    @Test
    fun readBytesCapped_rejectsOversizedStream() {
        val input = java.io.ByteArrayInputStream(ByteArray(32))
        try {
            RemoteBrowseHelpers.readBytesCapped(input, maxBytes = 16)
            throw AssertionError("expected size limit failure")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("MiB limit") || error.message!!.contains("exceeds"))
        }
    }

    @Test
    fun readBytesCapped_readsWithinLimit() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val bytes = RemoteBrowseHelpers.readBytesCapped(
            java.io.ByteArrayInputStream(payload),
            maxBytes = 16,
        )
        assertTrue(bytes.contentEquals(payload))
    }
}
