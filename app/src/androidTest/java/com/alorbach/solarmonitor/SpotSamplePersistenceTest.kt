package com.alorbach.solarmonitor

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alorbach.solarmonitor.data.local.SolarMonitorDatabase
import com.alorbach.solarmonitor.data.model.DeviceProfileEntity
import com.alorbach.solarmonitor.data.model.DeviceTransport
import com.alorbach.solarmonitor.data.model.SpotSampleEntity
import com.alorbach.solarmonitor.data.model.TariffPeriodEntity
import com.alorbach.solarmonitor.data.model.ArchiveSyncProgressEntity
import com.alorbach.solarmonitor.data.model.DayArchiveResult
import com.alorbach.solarmonitor.data.repository.SolarRepository
import com.alorbach.solarmonitor.data.security.CredentialStore
import com.alorbach.solarmonitor.data.settings.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpotSamplePersistenceTest {
    private lateinit var db: SolarMonitorDatabase
    private lateinit var repository: SolarRepository
    private var deviceId = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SolarMonitorDatabase::class.java).build()
        repository = SolarRepository(context, db, AppSettingsStore(context, CredentialStore(context)))
        deviceId = db.dao().insertDevice(
            DeviceProfileEntity(
                name = "Test inverter", serial = null, model = "Legacy SMA",
                transport = DeviceTransport.BLUETOOTH_LEGACY, btMac = null, passwordRef = null,
                plantName = null, ownerName = null, address = null, latitude = null, longitude = null,
                timezone = "UTC", locale = "en",
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun mixedCurrencyPortfolioDoesNotExposeAnEarningsTotal() = runBlocking {
        val secondId = db.dao().insertDevice(db.dao().getDeviceById(deviceId)!!.copy(id = 0, name = "Second"))
        db.dao().upsertTariffs(
            listOf(
                TariffPeriodEntity(deviceId = deviceId, validFromEpochDay = 0, validToEpochDay = null, pricePerKwh = 0.3, currency = "EUR"),
                TariffPeriodEntity(deviceId = secondId, validFromEpochDay = 0, validToEpochDay = null, pricePerKwh = 0.3, currency = "USD"),
            ),
        )

        val portfolio = repository.getPortfolioSummary()

        assertEquals(2, portfolio.deviceCount)
        assertNull(portfolio.currency)
        assertNull(portfolio.estimatedEarnings)
        assertEquals("EUR", repository.currencyForDevices(listOf(deviceId)))
        assertEquals("USD", repository.currencyForDevices(listOf(secondId)))
    }

    @Test
    fun csvImportPreservesStoredLiveTelemetry() = runBlocking {
        repository.saveSpotSamples(listOf(live()))
        val originalId = stored().id

        importCsv()

        assertMerged(stored())
        assertEquals(originalId, stored().id)
    }

    @Test
    fun liveSamplePreservesPreviouslyImportedEnergy() = runBlocking {
        importCsv()

        repository.saveLiveSample(deviceId, live(), "OK")

        assertMerged(stored())
    }

    @Test
    fun duplicateTimestampsInOneImportAreMergedBeforeInsert() = runBlocking {
        repository.importBundle(listOf(live(), csv()), emptyList(), emptyList(), emptyList())

        assertEquals(1, db.dao().getAllSpotSamples(deviceId).size)
        assertMerged(stored())
    }

    @Test
    fun concurrentLiveAndImportWritesPreserveBothSources() = runBlocking {
        listOf(
            async(Dispatchers.Default) { repository.saveSpotSamples(listOf(live())) },
            async(Dispatchers.Default) { importCsv() },
        ).awaitAll()

        assertEquals(1, db.dao().getAllSpotSamples(deviceId).size)
        assertMerged(stored())
    }

    @Test
    fun concurrentHistoryClearCannotLeaveArchiveProgressWithoutData() = runBlocking {
        val marker = ArchiveSyncProgressEntity(deviceId, 19675, 1_700_000_000L, "saved")
        val archive = DayArchiveResult(emptyList(), listOf(csv().copy(sourceType = "bluetooth_day_archive")))
        repeat(30) {
            listOf(
                async(Dispatchers.Default) { repository.saveAutomaticDayArchive(marker, archive, 19645) },
                async(Dispatchers.Default) { repository.clearDeviceHistory(deviceId) },
            ).awaitAll()
            val hasMarker = db.dao().getArchiveSyncProgress(deviceId).isNotEmpty()
            val hasData = db.dao().getAllSpotSamples(deviceId).isNotEmpty()
            assertEquals("Clear and archive must serialize as complete operations", hasMarker, hasData)
        }
    }

    @Test
    fun archiveMarkerFailureRollsBackSamplesAndHours() = runBlocking {
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_archive_marker BEFORE INSERT ON archive_sync_progress
            BEGIN SELECT RAISE(ABORT, 'Test marker write failed'); END
        """.trimIndent())
        var failed = false
        try {
            repository.saveAutomaticDayArchive(
                ArchiveSyncProgressEntity(deviceId, 19675, 1_700_000_000L, "saved"),
                DayArchiveResult(emptyList(), listOf(csv().copy(sourceType = "bluetooth_day_archive"))),
                19645,
            )
        } catch (_: android.database.sqlite.SQLiteException) {
            failed = true
        }
        assertTrue(failed)
        assertTrue(db.dao().getArchiveSyncProgress(deviceId).isEmpty())
        assertTrue(db.dao().getAllSpotSamples(deviceId).isEmpty())
        assertTrue(db.dao().getHourRange(listOf(deviceId), 0, Long.MAX_VALUE).isEmpty())
    }

    private suspend fun importCsv() {
        repository.importBundle(listOf(csv()), emptyList(), emptyList(), emptyList())
    }

    private suspend fun stored() = db.dao().getAllSpotSamples(deviceId).single()

    private fun assertMerged(sample: SpotSampleEntity) {
        assertEquals(1_500, sample.totalPac)
        assertEquals(1_200, sample.pdc1)
        assertEquals(500, sample.pac1)
        assertEquals(50.0, sample.frequencyHz!!, 0.0)
        assertEquals(42.0, sample.temperatureC!!, 0.0)
        assertEquals(138_413_515L, sample.eTotalWh)
        assertEquals("bluetooth_live", sample.sourceType)
    }

    private fun live() = SpotSampleEntity(
        deviceId = deviceId, timestampEpochSeconds = 1_700_000_000L,
        pdc1 = 1_200, pac1 = 500, totalPac = 1_500,
        frequencyHz = 50.0, temperatureC = 42.0, status = "OK", sourceType = "bluetooth_live",
    )

    private fun csv() = SpotSampleEntity(
        deviceId = deviceId, timestampEpochSeconds = 1_700_000_000L,
        totalPac = 1_400, eTotalWh = 138_413_515L, status = "CSV", sourceType = "day_csv",
    )
}
