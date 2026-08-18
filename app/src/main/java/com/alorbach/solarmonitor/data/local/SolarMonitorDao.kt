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
import com.alorbach.solarmonitor.data.model.DeviceYearYieldRow
import com.alorbach.solarmonitor.data.model.HourAggregateEntity
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

    @Query("SELECT * FROM device_profiles WHERE btMac = :mac COLLATE NOCASE LIMIT 1")
    suspend fun getDeviceByMac(mac: String): DeviceProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: DeviceProfileEntity): Long

    @Update
    suspend fun updateDevice(device: DeviceProfileEntity)

    @Query("DELETE FROM device_profiles WHERE id = :id")
    suspend fun deleteDevice(id: Long)

    @Query("SELECT * FROM tariff_periods WHERE deviceId = :deviceId ORDER BY validFromEpochDay")
    suspend fun getTariffs(deviceId: Long): List<TariffPeriodEntity>

    @Query("SELECT * FROM tariff_periods WHERE deviceId IN (:deviceIds) ORDER BY deviceId, validFromEpochDay")
    suspend fun getTariffsForDevices(deviceIds: List<Long>): List<TariffPeriodEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTariffs(tariffs: List<TariffPeriodEntity>)

    @Query("DELETE FROM tariff_periods WHERE deviceId = :deviceId")
    suspend fun deleteTariffsForDevice(deviceId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertImportSource(source: ImportSourceEntity): Long

    @Query("SELECT * FROM import_sources WHERE deviceId = :deviceId ORDER BY id DESC")
    suspend fun getImportSources(deviceId: Long): List<ImportSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSpotSamples(samples: List<SpotSampleEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDayAggregates(items: List<DayAggregateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMonthAggregates(items: List<MonthAggregateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHourAggregates(items: List<HourAggregateEntity>)

    @Query(
        "DELETE FROM hour_aggregates WHERE deviceId = :deviceId " +
            "AND hourEpochSeconds BETWEEN :fromHour AND :toHour",
    )
    suspend fun deleteHourAggregatesInRange(deviceId: Long, fromHour: Long, toHour: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvents(items: List<DeviceEventEntity>)

    @Query("SELECT * FROM spot_samples WHERE deviceId = :deviceId ORDER BY timestampEpochSeconds DESC LIMIT 1")
    suspend fun getLatestSpotSample(deviceId: Long): SpotSampleEntity?

    @Query("SELECT * FROM spot_samples WHERE deviceId = :deviceId ORDER BY timestampEpochSeconds DESC LIMIT :limit")
    suspend fun getRecentSpotSamples(deviceId: Long, limit: Int): List<SpotSampleEntity>

    @Query(
        "SELECT * FROM spot_samples WHERE deviceId = :deviceId " +
            "AND timestampEpochSeconds BETWEEN :fromEpochSeconds AND :toEpochSeconds " +
            "ORDER BY timestampEpochSeconds",
    )
    suspend fun getSpotSamplesInRange(
        deviceId: Long,
        fromEpochSeconds: Long,
        toEpochSeconds: Long,
    ): List<SpotSampleEntity>

    @Query("SELECT * FROM spot_samples WHERE deviceId = :deviceId ORDER BY timestampEpochSeconds")
    suspend fun getAllSpotSamples(deviceId: Long): List<SpotSampleEntity>

    @Query("DELETE FROM spot_samples WHERE deviceId = :deviceId")
    suspend fun deleteSpotSamplesForDevice(deviceId: Long)

    @Query("DELETE FROM day_aggregates WHERE deviceId = :deviceId")
    suspend fun deleteDayAggregatesForDevice(deviceId: Long)

    @Query("DELETE FROM month_aggregates WHERE deviceId = :deviceId")
    suspend fun deleteMonthAggregatesForDevice(deviceId: Long)

    @Query("DELETE FROM hour_aggregates WHERE deviceId = :deviceId")
    suspend fun deleteHourAggregatesForDevice(deviceId: Long)

    @Query("DELETE FROM device_events WHERE deviceId = :deviceId")
    suspend fun deleteEventsForDevice(deviceId: Long)

    @Transaction
    suspend fun clearDeviceHistory(deviceId: Long) {
        deleteSpotSamplesForDevice(deviceId)
        deleteDayAggregatesForDevice(deviceId)
        deleteMonthAggregatesForDevice(deviceId)
        deleteHourAggregatesForDevice(deviceId)
        deleteEventsForDevice(deviceId)
    }

    @Query(
        "SELECT * FROM hour_aggregates WHERE deviceId IN (:deviceIds) " +
            "AND hourEpochSeconds BETWEEN :fromHour AND :toHour " +
            "ORDER BY hourEpochSeconds",
    )
    suspend fun getHourRange(
        deviceIds: List<Long>,
        fromHour: Long,
        toHour: Long,
    ): List<HourAggregateEntity>

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
            "message = :message, preservedCopyPath = :copyPath WHERE id = :jobId",
    )
    suspend fun completeImportJob(
        jobId: Long,
        status: String,
        completedAt: Long,
        message: String?,
        copyPath: String?,
    )

    @Query(
        "UPDATE import_jobs SET status = 'FAILED', completedAtEpochSeconds = :completedAt, " +
            "message = :message WHERE status = 'RUNNING' AND createdAtEpochSeconds < :createdBeforeEpochSeconds",
    )
    suspend fun failRunningImportJobs(
        completedAt: Long,
        message: String,
        createdBeforeEpochSeconds: Long,
    ): Int

    @Query(
        "UPDATE import_jobs SET passwordCredentialId = :credentialId WHERE id = :jobId",
    )
    suspend fun setImportJobCredential(jobId: Long, credentialId: String?)

    @Query("SELECT * FROM import_jobs WHERE id = :jobId")
    suspend fun getImportJob(jobId: Long): ImportJobEntity?

    @Query("DELETE FROM import_jobs WHERE id = :jobId")
    suspend fun deleteImportJob(jobId: Long)

    @Query("DELETE FROM import_jobs")
    suspend fun deleteAllImportJobs()

    @Query("SELECT id FROM import_jobs")
    suspend fun listImportJobIds(): List<Long>

    @Query("SELECT passwordCredentialId FROM import_jobs WHERE passwordCredentialId IS NOT NULL")
    suspend fun listImportPasswordCredentialIds(): List<String>

    @Query(
        "SELECT COUNT(*) FROM import_jobs WHERE passwordCredentialId = :credentialId",
    )
    suspend fun countImportJobsWithCredential(credentialId: String): Int

    @Query("SELECT * FROM day_aggregates WHERE deviceId = :deviceId AND dateEpochDay BETWEEN :startDay AND :endDay ORDER BY dateEpochDay")
    suspend fun getDayRange(deviceId: Long, startDay: Long, endDay: Long): List<DayAggregateEntity>

    @Query(
        "SELECT * FROM day_aggregates WHERE deviceId IN (:deviceIds) " +
            "AND dateEpochDay BETWEEN :startDay AND :endDay ORDER BY dateEpochDay",
    )
    suspend fun getDayRangeForDevices(
        deviceIds: List<Long>,
        startDay: Long,
        endDay: Long,
    ): List<DayAggregateEntity>

    @Query("SELECT * FROM day_aggregates WHERE deviceId IN (:deviceIds) ORDER BY dateEpochDay")
    suspend fun getAllDaysForDevices(deviceIds: List<Long>): List<DayAggregateEntity>

    @Query(
        "SELECT COALESCE(SUM(totalYieldWh), 0) FROM day_aggregates " +
            "WHERE deviceId = :deviceId AND dateEpochDay BETWEEN :startDay AND :endDay",
    )
    suspend fun sumDayYieldWh(deviceId: Long, startDay: Long, endDay: Long): Long

    @Query(
        """
        SELECT deviceId AS deviceId,
               strftime('%Y', dateEpochDay * 86400, 'unixepoch') AS yearKey,
               SUM(totalYieldWh) AS yieldWh,
               MAX(powerW) AS peakPowerW
        FROM day_aggregates
        WHERE deviceId IN (:deviceIds)
        GROUP BY deviceId, yearKey
        """,
    )
    suspend fun getYearlyDayYieldsForDevices(deviceIds: List<Long>): List<DeviceYearYieldRow>

    @Query("SELECT * FROM month_aggregates WHERE deviceId = :deviceId ORDER BY monthKey")
    suspend fun getAllMonths(deviceId: Long): List<MonthAggregateEntity>

    @Query(
        "SELECT * FROM month_aggregates WHERE deviceId IN (:deviceIds) " +
            "AND monthKey >= :fromKey AND monthKey <= :toKey ORDER BY monthKey",
    )
    suspend fun getMonthRangeForDevices(
        deviceIds: List<Long>,
        fromKey: String,
        toKey: String,
    ): List<MonthAggregateEntity>

    @Query("SELECT * FROM month_aggregates WHERE deviceId IN (:deviceIds) ORDER BY monthKey")
    suspend fun getAllMonthsForDevices(deviceIds: List<Long>): List<MonthAggregateEntity>

    @Query("SELECT * FROM device_profiles ORDER BY name")
    suspend fun getAllDevices(): List<DeviceProfileEntity>

    @Transaction
    suspend fun importBundle(
        spotSamples: List<SpotSampleEntity>,
        dayAggregates: List<DayAggregateEntity>,
        monthAggregates: List<MonthAggregateEntity>,
        events: List<DeviceEventEntity>,
    ) {
        if (spotSamples.isNotEmpty()) upsertSpotSamples(spotSamples)
        if (dayAggregates.isNotEmpty()) upsertDayAggregates(dayAggregates)
        if (monthAggregates.isNotEmpty()) upsertMonthAggregates(monthAggregates)
        if (events.isNotEmpty()) upsertEvents(events)
    }

    @Transaction
    suspend fun replaceTariffs(deviceId: Long, tariffs: List<TariffPeriodEntity>) {
        deleteTariffsForDevice(deviceId)
        upsertTariffs(tariffs)
    }
}
