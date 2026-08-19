package com.alorbach.solarmonitor

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alorbach.solarmonitor.data.local.SolarMonitorDatabase
import com.alorbach.solarmonitor.data.model.DayAggregateEntity
import com.alorbach.solarmonitor.data.model.DeviceProfileEntity
import com.alorbach.solarmonitor.data.model.DeviceTransport
import com.alorbach.solarmonitor.data.model.SaveDeviceResult
import com.alorbach.solarmonitor.data.repository.SolarRepository
import com.alorbach.solarmonitor.data.security.CredentialStore
import com.alorbach.solarmonitor.data.settings.AppSettingsStore
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration3To4Test {
    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SolarMonitorDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migratesAndDeduplicatesMacs() {
        helper.createDatabase(dbName, 3).apply {
            execSQL(
                """
                INSERT INTO device_profiles
                (name, serial, model, transport, btMac, passwordRef, plantName, ownerName, address,
                 latitude, longitude, timezone, locale, decimalPoint, delimiter, dateFormat, enabled,
                 legacyCompatibilityMode)
                VALUES
                ('A', NULL, 'SMA', 'BLUETOOTH_LEGACY', 'aa:bb:cc:dd:ee:ff', '0000', NULL, NULL, NULL,
                 NULL, NULL, 'UTC', 'en', 'comma', 'semicolon', 'dd.MM.yyyy HH:mm', 1,
                 'SINGLE_INVERTER_LEGACY'),
                ('B', NULL, 'SMA', 'BLUETOOTH_LEGACY', 'AA:BB:CC:DD:EE:FF', '0000', NULL, NULL, NULL,
                 NULL, NULL, 'UTC', 'en', 'comma', 'semicolon', 'dd.MM.yyyy HH:mm', 1,
                 'SINGLE_INVERTER_LEGACY'),
                ('C', NULL, 'SMA', 'BLUETOOTH_LEGACY', '', '0000', NULL, NULL, NULL,
                 NULL, NULL, 'UTC', 'en', 'comma', 'semicolon', 'dd.MM.yyyy HH:mm', 1,
                 'SINGLE_INVERTER_LEGACY')
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(dbName, 4, true, SolarMonitorDatabase.MIGRATION_3_4).use { db ->
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='hour_aggregates'").use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
            db.query("SELECT id, btMac FROM device_profiles ORDER BY id").use { cursor ->
                val rows = mutableListOf<Pair<Long, String?>>()
                while (cursor.moveToNext()) {
                    rows += cursor.getLong(0) to cursor.getString(1)
                }
                assertEquals(3, rows.size)
                assertEquals("AA:BB:CC:DD:EE:FF", rows[0].second)
                assertNull(rows[1].second)
                assertNull(rows[2].second)
            }
        }
    }
}

@RunWith(AndroidJUnit4::class)
class SaveDeviceDuplicateMacTest {
    private lateinit var db: SolarMonitorDatabase
    private lateinit var repository: SolarRepository

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun rejectsDuplicateMacOnDifferentProfile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SolarMonitorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = AppSettingsStore(context, CredentialStore(context))
        repository = SolarRepository(context, db, settings)

        val first = repository.saveDevice(device("Inverter 1", "11:22:33:44:55:66"))
        assertTrue(first is SaveDeviceResult.Success)
        val firstId = (first as SaveDeviceResult.Success).deviceId

        val second = repository.saveDevice(device("Inverter 2", "11:22:33:44:55:66"))
        assertTrue(second is SaveDeviceResult.DuplicateMac)
        assertEquals(firstId, (second as SaveDeviceResult.DuplicateMac).existingDeviceId)

        val sameProfile = repository.saveDevice(
            device("Inverter 1 renamed", "11:22:33:44:55:66").copy(id = firstId),
        )
        assertTrue(sameProfile is SaveDeviceResult.Success)
    }

    @Test
    fun updatingExistingDeviceKeepsChildHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SolarMonitorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = AppSettingsStore(context, CredentialStore(context))
        repository = SolarRepository(context, db, settings)

        val created = repository.saveDevice(device("Inverter 1", "11:22:33:44:55:66"))
        val deviceId = (created as SaveDeviceResult.Success).deviceId
        db.dao().upsertDayAggregates(
            listOf(
                DayAggregateEntity(
                    deviceId = deviceId,
                    dateEpochDay = 20_000L,
                    totalYieldWh = 12_500L,
                ),
            ),
        )

        val renamed = repository.saveDevice(
            device("Inverter 1 renamed", "11:22:33:44:55:66").copy(id = deviceId),
        )
        assertTrue(renamed is SaveDeviceResult.Success)
        val days = db.dao().getDayRange(deviceId, 20_000L, 20_000L)
        assertEquals(1, days.size)
        assertEquals(12_500L, days.first().totalYieldWh)
    }

    private fun device(name: String, mac: String) = DeviceProfileEntity(
        name = name,
        serial = null,
        model = "Legacy SMA",
        transport = DeviceTransport.BLUETOOTH_LEGACY,
        btMac = mac,
        passwordRef = "0000",
        plantName = null,
        ownerName = null,
        address = null,
        latitude = null,
        longitude = null,
        timezone = ZoneId.systemDefault().id,
        locale = Locale.getDefault().toLanguageTag(),
    )
}
