package com.alorbach.solarmonitor

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alorbach.solarmonitor.data.local.SolarMonitorDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveProgressMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SolarMonitorDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration5To6PreservesHistoryAndAddsEmptyCascadingProgress() {
        val name = "archive-progress-migration.db"
        helper.createDatabase(name, 5).use { db ->
            db.execSQL("""
                INSERT INTO device_profiles
                (id, name, transport, timezone, locale, decimalPoint, delimiter, dateFormat,
                 enabled, legacyCompatibilityMode)
                VALUES (7, 'Existing inverter', 'BLUETOOTH_LEGACY', 'Europe/Berlin', 'de',
                    'comma', 'semicolon', 'dd.MM.yyyy HH:mm', 1, 'SINGLE_INVERTER_LEGACY')
            """.trimIndent())
            db.execSQL("""
                INSERT INTO spot_samples (deviceId, timestampEpochSeconds, eTotalWh, sourceType)
                VALUES (7, 1700000000, 138413515, 'bluetooth_day_archive')
            """.trimIndent())
        }

        helper.runMigrationsAndValidate(name, 6, true, SolarMonitorDatabase.MIGRATION_5_6).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            db.query("SELECT name, timezone FROM device_profiles WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Existing inverter", cursor.getString(0))
                assertEquals("Europe/Berlin", cursor.getString(1))
            }
            db.query("SELECT eTotalWh FROM spot_samples WHERE deviceId = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(138413515L, cursor.getLong(0))
            }
            db.query("SELECT * FROM archive_sync_progress").use { cursor ->
                assertFalse("Migration must not assume existing samples prove a completed archive read", cursor.moveToFirst())
            }
            db.execSQL("""
                INSERT INTO archive_sync_progress
                (deviceId, dateEpochDay, attemptedAtEpochSeconds, outcome)
                VALUES (7, 19675, 1700000000, 'saved')
            """.trimIndent())
            db.execSQL("DELETE FROM device_profiles WHERE id = 7")
            db.query("SELECT * FROM archive_sync_progress").use { cursor ->
                assertFalse("Deleting a device must remove its archive progress", cursor.moveToFirst())
            }
            db.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }
        }
    }
}
