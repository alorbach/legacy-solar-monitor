package com.alorbach.solarmonitor.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.alorbach.solarmonitor.data.model.DayAggregateEntity
import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import com.alorbach.solarmonitor.data.model.DeviceProfileEntity
import com.alorbach.solarmonitor.data.model.HourAggregateEntity
import com.alorbach.solarmonitor.data.model.ImportJobEntity
import com.alorbach.solarmonitor.data.model.ImportSourceEntity
import com.alorbach.solarmonitor.data.model.MonthAggregateEntity
import com.alorbach.solarmonitor.data.model.SpotSampleEntity
import com.alorbach.solarmonitor.data.model.TariffPeriodEntity

@Database(
    entities = [
        DeviceProfileEntity::class,
        ImportSourceEntity::class,
        TariffPeriodEntity::class,
        SpotSampleEntity::class,
        DayAggregateEntity::class,
        MonthAggregateEntity::class,
        HourAggregateEntity::class,
        DeviceEventEntity::class,
        ImportJobEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class SolarMonitorDatabase : RoomDatabase() {
    abstract fun dao(): SolarMonitorDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hour_aggregates` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `deviceId` INTEGER NOT NULL,
                        `hourEpochSeconds` INTEGER NOT NULL,
                        `yieldWh` INTEGER NOT NULL,
                        `maxPowerW` INTEGER,
                        `sourceType` TEXT NOT NULL,
                        FOREIGN KEY(`deviceId`) REFERENCES `device_profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_hour_aggregates_deviceId_hourEpochSeconds` " +
                        "ON `hour_aggregates` (`deviceId`, `hourEpochSeconds`)",
                )

                // Normalize blank MACs to NULL and uppercase remaining values.
                db.execSQL(
                    "UPDATE device_profiles SET btMac = NULL WHERE btMac IS NOT NULL AND TRIM(btMac) = ''",
                )
                db.execSQL(
                    "UPDATE device_profiles SET btMac = UPPER(btMac) WHERE btMac IS NOT NULL",
                )

                // Keep the lowest id for each MAC; clear duplicates so the unique index can be created.
                db.execSQL(
                    """
                    UPDATE device_profiles
                    SET btMac = NULL
                    WHERE id NOT IN (
                        SELECT MIN(id) FROM device_profiles WHERE btMac IS NOT NULL GROUP BY btMac
                    )
                    AND btMac IS NOT NULL
                    """.trimIndent(),
                )

                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_device_profiles_btMac` ON `device_profiles` (`btMac`)",
                )
            }
        }

        fun create(context: Context): SolarMonitorDatabase =
            Room.databaseBuilder(
                context,
                SolarMonitorDatabase::class.java,
                "solar-monitor.db",
            )
                .addMigrations(MIGRATION_3_4)
                // Pre-v3 installs used destructive upgrades with unknown intermediate schemas.
                // Wipe those once; all future bumps must ship real Migration objects from v3 onward.
                .fallbackToDestructiveMigrationFrom(1, 2)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
