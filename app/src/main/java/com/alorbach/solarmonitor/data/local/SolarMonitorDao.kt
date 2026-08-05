package com.alorbach.solarmonitor.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.alorbach.solarmonitor.data.model.DayAggregateEntity
import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import com.alorbach.solarmonitor.data.model.DeviceProfileEntity
import com.alorbach.solarmonitor.data.model.ImportJobEntity
import com.alorbach.solarmonitor.data.model.ImportSourceEntity
import com.alorbach.solarmonitor.data.model.MonthAggregateEntity
import com.alorbach.solarmonitor.data.model.SpotSampleEntity
import com.alorbach.solarmonitor.data.model.TariffPeriodEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SolarMonitorDao {
    @Query("SELECT * FROM device_profiles ORDER BY name")
    fun observeDevices(): Flow<List<DeviceProfileEntity>>

    @Query("SELECT * FROM device_profiles WHERE id = :id")
    suspend fun getDeviceById(id: Long): DeviceProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: DeviceProfileEntity): Long

    @Update
    suspend fun updateDevice(device: DeviceProfileEntity)

    @Query("DELETE FROM device_profiles WHERE id = :id")
    suspend fun deleteDevice(id: Long)

    @Query("SELECT * FROM tariff_periods WHERE deviceId = :deviceId ORDER BY validFromEpochDay")
    suspend fun getTariffs(deviceId: Long): List<TariffPeriodEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTariffs(tariffs: List<TariffPeriodEntity>)

    @Query("DELETE FROM tariff_periods WHERE deviceId = :deviceId")
    suspend fun deleteTariffsForDevice(deviceId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertImportSource(source: ImportSourceEntity): Long

    @Query("SELECT * FROM import_sources WHERE deviceId = :deviceId ORDER BY id DESC")
    suspend fun getImportSources(deviceId: Long): List<ImportSourceEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSpotSamples(samples: List<SpotSampleEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDayAggregates(items: List<DayAggregateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMonthAggregates(items: List<MonthAggregateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvents(items: List<DeviceEventEntity>)

    @Query("SELECT * FROM spot_samples WHERE deviceId = :deviceId ORDER BY timestampEpochSeconds DESC LIMIT 1")
    suspend fun getLatestSpotSample(deviceId: Long): SpotSampleEntity?

    @Query("SELECT * FROM spot_samples WHERE deviceId = :deviceId ORDER BY timestampEpochSeconds DESC LIMIT :limit")
    suspend fun getRecentSpotSamples(deviceId: Long, limit: Int): List<SpotSampleEntity>

    @Query("SELECT * FROM day_aggregates WHERE deviceId = :deviceId ORDER BY dateEpochDay DESC LIMIT :limit")
    suspend fun getRecentDayAggregates(deviceId: Long, limit: Int): List<DayAggregateEntity>

    @Query("SELECT * FROM month_aggregates WHERE deviceId = :deviceId ORDER BY monthKey DESC LIMIT :limit")
    suspend fun getRecentMonthAggregates(deviceId: Long, limit: Int): List<MonthAggregateEntity>

    @Query("SELECT * FROM device_events WHERE deviceId = :deviceId ORDER BY timestampEpochSeconds DESC LIMIT :limit")
    suspend fun getRecentEvents(deviceId: Long, limit: Int): List<DeviceEventEntity>

    @Query("SELECT * FROM import_jobs ORDER BY createdAtEpochSeconds DESC LIMIT 50")
    fun observeImportJobs(): Flow<List<ImportJobEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImportJob(job: ImportJobEntity): Long

    @Query(
        "UPDATE import_jobs SET status = :status, completedAtEpochSeconds = :completedAt, " +
            "message = :message, preservedCopyPath = :copyPath WHERE id = :jobId"
    )
    suspend fun completeImportJob(
        jobId: Long,
        status: String,
        completedAt: Long,
        message: String?,
        copyPath: String?,
    )

    @Query("SELECT * FROM day_aggregates WHERE deviceId = :deviceId AND dateEpochDay BETWEEN :startDay AND :endDay ORDER BY dateEpochDay")
    suspend fun getDayRange(deviceId: Long, startDay: Long, endDay: Long): List<DayAggregateEntity>

    @Query("SELECT * FROM month_aggregates WHERE deviceId = :deviceId ORDER BY monthKey")
    suspend fun getAllMonths(deviceId: Long): List<MonthAggregateEntity>

    @Query("SELECT * FROM device_profiles ORDER BY name")
    suspend fun getAllDevices(): List<DeviceProfileEntity>

    @Transaction
    suspend fun replaceTariffs(deviceId: Long, tariffs: List<TariffPeriodEntity>) {
        deleteTariffsForDevice(deviceId)
        upsertTariffs(tariffs)
    }
}
