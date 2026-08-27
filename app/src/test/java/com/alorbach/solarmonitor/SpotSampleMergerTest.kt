package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.local.SpotSampleMerger
import com.alorbach.solarmonitor.data.model.SpotSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotSampleMergerTest {
    @Test
    fun archiveCollisionPreservesRicherLiveTelemetry() {
        val live = sample(
            id = 7L,
            source = "bluetooth_live",
            pdc1 = 1_200,
            pac1 = 500,
            totalPac = 1_500,
            frequencyHz = 50.0,
            temperatureC = 42.0,
            status = "OK",
        )
        val archive = sample(
            source = "bluetooth_day_archive",
            totalPac = 1_400,
            eTotalWh = 138_413_515L,
            status = "Archive",
        )

        val merged = SpotSampleMerger.merge(live, archive)

        assertEquals(7L, merged.id)
        assertEquals(1_200, merged.pdc1)
        assertEquals(500, merged.pac1)
        assertEquals(1_500, merged.totalPac)
        assertEquals(50.0, merged.frequencyHz ?: error("frequency missing"), 0.0)
        assertEquals(42.0, merged.temperatureC ?: error("temperature missing"), 0.0)
        assertEquals("OK", merged.status)
        assertEquals(138_413_515L, merged.eTotalWh)
        assertEquals("bluetooth_day_archive", merged.sourceType)
    }

    @Test
    fun incomingArchiveReplacesStaleArchiveFields() {
        val existing = sample(
            source = "bluetooth_day_archive",
            totalPac = 1_400,
            eTotalWh = 138_393_515L,
            status = "Old",
        )
        val incoming = sample(
            source = "bluetooth_day_archive",
            totalPac = 1_500,
            eTotalWh = 138_413_515L,
            status = "New",
        )

        val merged = SpotSampleMerger.merge(existing, incoming)

        assertEquals(1_500, merged.totalPac)
        assertEquals(138_413_515L, merged.eTotalWh)
        assertEquals("New", merged.status)
        assertEquals("bluetooth_day_archive", merged.sourceType)
    }

    @Test
    fun incomingLivePreservesArchiveEnergyFields() {
        val archive = sample(
            id = 7L,
            source = "bluetooth_day_archive",
            totalPac = 1_400,
            eTotalWh = 138_413_515L,
        )
        val live = sample(
            source = "bluetooth_live",
            pdc1 = 1_200,
            pac1 = 500,
            totalPac = 1_500,
            frequencyHz = 50.0,
            temperatureC = 42.0,
            status = "OK",
        )

        val merged = SpotSampleMerger.merge(archive, live)

        assertEquals(7L, merged.id)
        assertEquals(1_200, merged.pdc1)
        assertEquals(500, merged.pac1)
        assertEquals(1_500, merged.totalPac)
        assertEquals(138_413_515L, merged.eTotalWh)
        assertEquals("OK", merged.status)
        assertEquals("bluetooth_day_archive", merged.sourceType)
    }

    private fun sample(
        id: Long = 0L,
        source: String,
        pdc1: Int? = null,
        pac1: Int? = null,
        totalPac: Int? = null,
        eTotalWh: Long? = null,
        frequencyHz: Double? = null,
        temperatureC: Double? = null,
        status: String? = null,
    ) = SpotSampleEntity(
        id = id,
        deviceId = 1L,
        timestampEpochSeconds = 1_700_000_000L,
        pdc1 = pdc1,
        pac1 = pac1,
        totalPac = totalPac,
        eTotalWh = eTotalWh,
        frequencyHz = frequencyHz,
        temperatureC = temperatureC,
        status = status,
        sourceType = source,
    )
}
