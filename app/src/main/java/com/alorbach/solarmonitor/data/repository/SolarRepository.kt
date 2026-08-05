package com.alorbach.solarmonitor.data.repository

import android.content.Context
import com.alorbach.solarmonitor.data.local.SolarMonitorDatabase
import com.alorbach.solarmonitor.data.model.DailyPoint
import com.alorbach.solarmonitor.data.model.DayAggregateEntity
import com.alorbach.solarmonitor.data.model.DeviceDashboardSummary
import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import com.alorbach.solarmonitor.data.model.DeviceProfileEntity
import com.alorbach.solarmonitor.data.model.ImportJobEntity
import com.alorbach.solarmonitor.data.model.ImportSourceEntity
import com.alorbach.solarmonitor.data.model.MonthAggregateEntity
import com.alorbach.solarmonitor.data.model.MonthlyPoint
import com.alorbach.solarmonitor.data.model.PortfolioSummary
import com.alorbach.solarmonitor.data.model.SpotSampleEntity
import com.alorbach.solarmonitor.data.model.TariffPeriodEntity
import com.alorbach.solarmonitor.data.settings.AppSettingsStore
import com.alorbach.solarmonitor.domain.EarningsCalculator
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SolarRepository(
    private val appContext: Context,
    private val db: SolarMonitorDatabase,
    private val settingsStore: AppSettingsStore,
) {
    private val dao = db.dao()

    fun observeDevices(): Flow<List<DeviceProfileEntity>> = dao.observeDevices()

    fun observeImportJobs(): Flow<List<ImportJobEntity>> = dao.observeImportJobs()

    suspend fun saveDevice(device: DeviceProfileEntity): Long = dao.upsertDevice(device)

    suspend fun deleteDevice(deviceId: Long) = dao.deleteDevice(deviceId)

    suspend fun getDevice(deviceId: Long): DeviceProfileEntity? = dao.getDeviceById(deviceId)

    suspend fun saveTariffs(deviceId: Long, tariffs: List<TariffPeriodEntity>) {
        dao.replaceTariffs(deviceId, tariffs.map { it.copy(deviceId = deviceId) })
    }

    suspend fun getTariffs(deviceId: Long): List<TariffPeriodEntity> = dao.getTariffs(deviceId)

    suspend fun saveImportSource(source: ImportSourceEntity): Long = dao.upsertImportSource(source)

    suspend fun getImportSources(deviceId: Long): List<ImportSourceEntity> = dao.getImportSources(deviceId)

    suspend fun saveSpotSamples(samples: List<SpotSampleEntity>) {
        if (samples.isNotEmpty()) dao.insertSpotSamples(samples)
    }

    suspend fun saveLiveSample(deviceId: Long, sample: SpotSampleEntity, status: String) {
        saveSpotSamples(listOf(sample))
        updateDeviceStatus(
            deviceId = deviceId,
            status = status,
            liveAtEpochSeconds = sample.timestampEpochSeconds,
        )
    }

    suspend fun saveDayAggregates(items: List<DayAggregateEntity>) {
        if (items.isNotEmpty()) dao.upsertDayAggregates(items)
    }

    suspend fun saveMonthAggregates(items: List<MonthAggregateEntity>) {
        if (items.isNotEmpty()) dao.upsertMonthAggregates(items)
    }

    suspend fun saveArchiveSync(
        deviceId: Long,
        dayItems: List<DayAggregateEntity>,
        monthItems: List<MonthAggregateEntity>,
        status: String,
    ) {
        if (dayItems.isNotEmpty()) dao.upsertDayAggregates(dayItems)
        if (monthItems.isNotEmpty()) dao.upsertMonthAggregates(monthItems)
        updateDeviceStatus(
            deviceId = deviceId,
            status = status,
            archiveAtEpochSeconds = System.currentTimeMillis() / 1000,
        )
    }

    suspend fun saveEvents(items: List<DeviceEventEntity>) {
        if (items.isNotEmpty()) dao.upsertEvents(items)
    }

    suspend fun recordImportJob(job: ImportJobEntity): Long = dao.insertImportJob(job)

    suspend fun completeImportJob(jobId: Long, success: Boolean, message: String?, copyPath: String?) {
        dao.completeImportJob(
            jobId = jobId,
            status = if (success) "SUCCEEDED" else "FAILED",
            completedAt = System.currentTimeMillis() / 1000,
            message = message,
            copyPath = copyPath,
        )
    }

    suspend fun getDeviceDashboard(deviceId: Long): DeviceDashboardSummary? {
        val device = dao.getDeviceById(deviceId) ?: return null
        val latest = dao.getLatestSpotSample(deviceId)
        val days = dao.getRecentDayAggregates(deviceId, 32)
        val months = dao.getRecentMonthAggregates(deviceId, 12)
        val tariffs = dao.getTariffs(deviceId)

        val today = days.firstOrNull()
        val monthYield = months.firstOrNull()?.dayYieldWh
        val yearYield = months.filter { it.monthKey.startsWith(YearMonth.now().year.toString()) }.sumOf { it.dayYieldWh }
        val earnings = days.sumOf { EarningsCalculator.earningsForDay(it, tariffs) }

        return DeviceDashboardSummary(
            deviceId = device.id,
            deviceName = device.name,
            model = device.model,
            currentPowerW = latest?.totalPac,
            todayYieldWh = latest?.eTodayWh ?: today?.totalYieldWh,
            monthYieldWh = monthYield,
            yearlyYieldWh = yearYield,
            estimatedEarnings = earnings,
            currency = tariffs.firstOrNull()?.currency,
            status = device.lastConnectionStatus ?: latest?.status,
            lastUpdateEpochSeconds = device.lastLiveReadAtEpochSeconds ?: latest?.timestampEpochSeconds,
        )
    }

    suspend fun getPortfolioSummary(): PortfolioSummary {
        val devices = dao.getAllDevices()
        val summaries = devices.mapNotNull { getDeviceDashboard(it.id) }
        return PortfolioSummary(
            deviceCount = summaries.size,
            currentPowerW = summaries.sumOf { it.currentPowerW ?: 0 },
            todayYieldWh = summaries.sumOf { it.todayYieldWh ?: 0 },
            monthYieldWh = summaries.sumOf { it.monthYieldWh ?: 0 },
            yearYieldWh = summaries.sumOf { it.yearlyYieldWh ?: 0 },
            estimatedEarnings = summaries.sumOf { it.estimatedEarnings },
            currency = summaries.firstOrNull()?.currency,
        )
    }

    suspend fun getDailyChart(deviceId: Long, days: Int = 30): List<DailyPoint> {
        val end = LocalDate.now().toEpochDay()
        val start = LocalDate.now().minusDays(days.toLong()).toEpochDay()
        val tariffs = dao.getTariffs(deviceId)
        return dao.getDayRange(deviceId, start, end).map {
            DailyPoint(
                dateEpochDay = it.dateEpochDay,
                yieldWh = it.totalYieldWh,
                earnings = EarningsCalculator.earningsForDay(it, tariffs),
            )
        }
    }

    suspend fun getMonthlyChart(deviceId: Long): List<MonthlyPoint> {
        val tariffs = dao.getTariffs(deviceId)
        return dao.getAllMonths(deviceId).map {
            MonthlyPoint(
                monthKey = it.monthKey,
                yieldWh = it.dayYieldWh,
                earnings = EarningsCalculator.earningsForMonth(it, tariffs),
            )
        }
    }

    suspend fun getRecentEvents(deviceId: Long, limit: Int = 25): List<DeviceEventEntity> =
        dao.getRecentEvents(deviceId, limit)

    suspend fun getRecentSpotSamples(deviceId: Long, limit: Int = 60): List<SpotSampleEntity> =
        dao.getRecentSpotSamples(deviceId, limit)

    fun observeCloudBackupEnabled(): Flow<Boolean> = settingsStore.settings.map { it.cloudBackupEnabled }

    suspend fun storeImportedCopy(relativeName: String, bytes: ByteArray): String {
        val targetDir = appContext.getDir("imports", Context.MODE_PRIVATE)
        val file = targetDir.resolve(relativeName)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file.absolutePath
    }

    suspend fun updateDeviceStatus(
        deviceId: Long,
        status: String,
        liveAtEpochSeconds: Long? = null,
        archiveAtEpochSeconds: Long? = null,
        socketStrategy: String? = null,
        diagnostics: String? = null,
    ) {
        val device = dao.getDeviceById(deviceId) ?: return
        dao.updateDevice(
            device.copy(
                lastConnectionStatus = status,
                lastSuccessfulSocketStrategy = socketStrategy ?: device.lastSuccessfulSocketStrategy,
                lastDiagnostics = diagnostics ?: device.lastDiagnostics,
                lastLiveReadAtEpochSeconds = liveAtEpochSeconds ?: device.lastLiveReadAtEpochSeconds,
                lastArchiveSyncAtEpochSeconds = archiveAtEpochSeconds ?: device.lastArchiveSyncAtEpochSeconds,
            )
        )
    }
}
