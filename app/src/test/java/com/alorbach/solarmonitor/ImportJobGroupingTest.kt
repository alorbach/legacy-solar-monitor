package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.importing.groupImportJobs
import com.alorbach.solarmonitor.data.importing.publicUrlSourceLabel
import com.alorbach.solarmonitor.data.model.ImportJobEntity
import com.alorbach.solarmonitor.data.model.ImportJobStatus
import com.alorbach.solarmonitor.data.model.ImportSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ImportJobGroupingTest {
    @Test
    fun sameDeviceAndLabelCollapseToOneGroup() {
        val older = job(id = 1, deviceId = 7, label = "FTP folder 172.21.0.30:/smadata", created = 10)
        val newer = job(id = 2, deviceId = 7, label = "FTP folder 172.21.0.30:/smadata", created = 20)
        val groups = groupImportJobs(listOf(older, newer))
        assertEquals(1, groups.size)
        assertEquals(2L, groups[0].latest.id)
        assertEquals(1, groups[0].history.size)
        assertEquals(1L, groups[0].history[0].id)
    }

    @Test
    fun differentDevicesStaySeparate() {
        val a = job(id = 1, deviceId = 1, label = "same", created = 1)
        val b = job(id = 2, deviceId = 2, label = "same", created = 2)
        assertEquals(2, groupImportJobs(listOf(a, b)).size)
    }

    @Test
    fun publicUrlSourceLabel_stripsUserinfoAndQuery() {
        assertEquals(
            "https://files.example/sma/day.csv",
            publicUrlSourceLabel("https://user:token@files.example/sma/day.csv?token=secret#frag"),
        )
        assertEquals(
            "http://192.168.1.10/data",
            publicUrlSourceLabel("http://192.168.1.10/data"),
        )
        val malformed = publicUrlSourceLabel("https://user:token@files.example/sma/day.csv token")
        assertFalse(malformed.contains("user:token"))
        assertFalse(malformed.contains("@"))
    }

    @Test
    fun differentUrlLabelsStaySeparate() {
        val a = job(id = 1, deviceId = 1, label = "https://a.example/day.csv", created = 1)
        val b = job(id = 2, deviceId = 1, label = "https://b.example/day.csv", created = 2)
        assertEquals(2, groupImportJobs(listOf(a, b)).size)
    }

    @Test
    fun trimsSourceLabel() {
        val a = job(id = 1, deviceId = 1, label = "URL import", created = 1)
        val b = job(id = 2, deviceId = 1, label = " URL import ", created = 2)
        assertEquals(1, groupImportJobs(listOf(a, b)).size)
    }

    @Test
    fun urlJobsWithCredentialsGroupWithRedactedLabel() {
        val raw = job(
            id = 1,
            deviceId = 1,
            label = "https://user:token@files.example/sma/day.csv?token=secret",
            created = 1,
            sourceType = ImportSourceType.URL,
        )
        val redacted = job(
            id = 2,
            deviceId = 1,
            label = "https://files.example/sma/day.csv",
            created = 2,
            sourceType = ImportSourceType.URL,
        )
        val groups = groupImportJobs(listOf(raw, redacted))
        assertEquals(1, groups.size)
        assertEquals(2L, groups[0].latest.id)
    }

    private fun job(
        id: Long,
        deviceId: Long,
        label: String,
        created: Long,
        sourceType: ImportSourceType = ImportSourceType.FTP,
    ) = ImportJobEntity(
        id = id,
        deviceId = deviceId,
        sourceLabel = label,
        sourceType = sourceType,
        status = ImportJobStatus.SUCCEEDED,
        createdAtEpochSeconds = created,
        completedAtEpochSeconds = created,
    )
}
