package com.alorbach.solarmonitor.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.alorbach.solarmonitor.data.model.DayAggregateEntity
import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import com.alorbach.solarmonitor.data.model.DeviceProfileEntity
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
        DeviceEventEntity::class,
        ImportJobEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class SolarMonitorDatabase : RoomDatabase() {
    abstract fun dao(): SolarMonitorDao

    companion object {
        fun create(context: Context): SolarMonitorDatabase =
            Room.databaseBuilder(
                context,
                SolarMonitorDatabase::class.java,
                "solar-monitor.db",
            ).fallbackToDestructiveMigration().build()
    }
}
