package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.cloud.GoogleDriveRemote
import java.io.File
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveRemoteTest {
    @Test
    fun createFileMultipart_usesRequestBodyMediaTypesNotPartHeaders() {
        val file = File.createTempFile("solar-monitor", ".db")
        file.writeBytes(byteArrayOf(0x53, 0x51, 0x4c, 0x69, 0x74, 0x65))
        val metadata = """{"name":"solar-monitor.db","parents":["folder-id"]}"""
        val body = GoogleDriveRemote.createFileMultipart(
            metadataJson = metadata,
            file = file,
            boundary = "test-boundary",
        )

        assertEquals("multipart", body.contentType()?.type)
        assertEquals("related", body.contentType()?.subtype)
        assertEquals(2, body.parts.size)
        assertEquals("application/json; charset=UTF-8", body.parts[0].body.contentType().toString())
        assertEquals("application/octet-stream", body.parts[1].body.contentType().toString())

        val written = Buffer().also { body.writeTo(it) }.readUtf8()
        assertTrue(written.contains("Content-Type: application/json"))
        assertTrue(written.contains("Content-Type: application/octet-stream"))
        assertTrue(written.contains(metadata))
        assertTrue(written.contains("SQLite"))
        file.delete()
    }
}
