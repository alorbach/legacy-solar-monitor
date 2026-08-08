package com.alorbach.solarmonitor.data.repository

import android.content.Context
import com.alorbach.solarmonitor.data.local.SolarMonitorDatabase
import com.alorbach.solarmonitor.data.model.DailyPoint
import com.alorbach.solarmonitor.data.model.DayAggregateEntity
import com.alorbach.solarmonitor.data.model.DeviceDashboardSummary
import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import com.alorbach.solarmonitor.data.model.DeviceProfileEntity
import com.alorbach.solarmonitor.data.model.HourAggregateEntity
import com.alorbach.solarmonitor.data.model.ImportJobEntity
import com.alorbach.solarmonitor.data.model.ImportSourceEntity
import com.alorbach.solarmonitor.data.model.MonthAggregateEntity
import com.alorbach.solarmonitor.data.model.MonthlyPoint
import com.alorbach.solarmonitor.data.model.PortfolioSummary
import com.alorbach.solarmonitor.data.model.SaveDeviceResult
import com.alorbach.solarmonitor.data.model.SpotSampleEntity
import com.alorbach.solarmonitor.data.model.StatsPoint
import com.alorbach.solarmonitor.data.model.TariffPeriodEntity
import com.alorbach.solarmonitor.data.settings.AppSettingsStore
import com.alorbach.solarmonitor.domain.EarningsCalculator
import com.alorbach.solarmonitor.domain.StatisticsAggregator
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SolarRepository(
    private val appContext: Context,
    private val db: SolarMonitorDatabase,
    private val settingsStore: AppSettingsStore,
) {
    private val dao = db.dao()
    private val hourRecomputeMutexes = ConcurrentHashMap<Long, Mutex>()

    private fun hourRecomputeMutex(deviceId: Long): Mutex =
        hourRecomputeMutexes.getOrPut(deviceId) { Mutex() }

    fun observeDevices(): Flow<List<DeviceProfileEntity>> = dao.observeDevices()

    fun observeImportJobs(): Flow<List<ImportJobEntity>> = dao.observeImportJobs()

    data class DeviceUpsert(
        val id: Long,
        val created: Boolean,
    )

    suspend fun saveDevice(device: DeviceProfileEntity): SaveDeviceResult {
        val normalizedMac = device.btMac?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        if (normalizedMac != null) {
            val existing = dao.getDeviceByMac(normalizedMac)
            if (existing != null && existing.id != device.id) {
                return SaveDeviceResult.DuplicateMac(existing.id, normalizedMac)
            }
        }
        val id = dao.upsertDevice(device.copy(btMac = normalizedMac))
        return SaveDeviceResult.Success(if (device.id == 0L) id else device.id)
    }

    /**
     * Insert-or-return for Bluetooth discovery: if the MAC already has a profile, return that id
     * without creating a duplicate.
     */
    suspend fun saveDeviceForMac(device: DeviceProfileEntity): DeviceUpsert {
        val normalizedMac = device.btMac?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        if (normalizedMac != null) {
            dao.getDeviceByMac(normalizedMac)?.let { return DeviceUpsert(it.id, created = false) }
        }
        return when (val result = saveDevice(device.copy(btMac = normalizedMac))) {
            is SaveDeviceResult.Success -> DeviceUpsert(result.deviceId, created = true)
            is SaveDeviceResult.DuplicateMac -> DeviceUpsert(result.existingDeviceId, created = false)
        }
    }

    /** Update an existing profile. Returns false when the MAC belongs to another profile. */
    suspend fun saveEditedDevice(device: DeviceProfileEntity): Boolean =
        saveDevice(device) is SaveDeviceResult.Success

    suspend fun deleteDevice(deviceId: Long) = dao.deleteDevice(deviceId)

    suspend fun getDevice(deviceId: Long): DeviceProfileEntity? = dao.getDeviceById(deviceId)

    suspend fun getDeviceByMac(mac: String): DeviceProfileEntity? = dao.getDeviceByMac(mac)

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
        val zoneId = deviceZone(deviceId)
        val hourStart = StatisticsAggregator.hourStartEpochSeconds(sample.timestampEpochSeconds, zoneId)
        recomputeHourAggregates(deviceId, hourStart - 3600, hourStart + 3599)
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
        spotSamples: List<SpotSampleEntity> = emptyList(),
        status: String,
    ) {
        if (spotSamples.isNotEmpty()) dao.insertSpotSamples(spotSamples)
        if (dayItems.isNotEmpty()) dao.upsertDayAggregates(dayItems)
        if (monthItems.isNotEmpty()) dao.upsertMonthAggregates(monthItems)
        if (spotSamples.isNotEmpty()) {
            val from = spotSamples.minOf { it.timestampEpochSeconds }
            val to = spotSamples.maxOf { it.timestampEpochSeconds }
            recomputeHourAggregates(deviceId, from, to)
        }
        updateDeviceStatus(
            deviceId = deviceId,
            status = status,
            archiveAtEpochSeconds = System.currentTimeMillis() / 1000,
        )
    }

    suspend fun saveEvents(items: List<DeviceEventEntity>) {
        if (items.isNotEmpty()) dao.upsertEvents(items)
    }

    suspend fun importBundle(
        spotSamples: List<SpotSampleEntity>,
        dayAggregates: List<DayAggregateEntity>,
        monthAggregates: List<MonthAggregateEntity>,
        events: List<DeviceEventEntity>,
    ) {
        dao.importBundle(spotSamples, dayAggregates, monthAggregates, events)
        spotSamples.groupBy { it.deviceId }.forEach { (deviceId, samples) ->
            if (samples.isNotEmpty()) {
                recomputeHourAggregates(
                    deviceId = deviceId,
                    fromEpochSeconds = samples.minOf { it.timestampEpochSeconds },
                    toEpochSeconds = samples.maxOf { it.timestampEpochSeconds },
                )
            }
        }
    }

    suspend fun recomputeHourAggregates(
        deviceId: Long,
        fromEpochSeconds: Long,
        toEpochSeconds: Long,
    ) {
        hourRecomputeMutex(deviceId).withLock {
            val zoneId = deviceZone(deviceId)
            val fromHour = StatisticsAggregator.hourStartEpochSeconds(fromEpochSeconds, zoneId)
            val toHour = StatisticsAggregator.hourStartEpochSeconds(toEpochSeconds, zoneId)
            // Include one hour of lookback so the first bucket has a baseline total.
            val lookback = fromHour - 3600
            val samples = dao.getSpotSamplesInRange(deviceId, lookback, toHour + 3599)
            val aggregates = StatisticsAggregator.hourAggregatesFromSamples(
                deviceId = deviceId,
                samples = samples,
                zoneId = zoneId,
            ).filter { it.hourEpochSeconds in fromHour..toHour }
            dao.deleteHourAggregatesInRange(deviceId, fromHour, toHour)
            if (aggregates.isNotEmpty()) {
                dao.upsertHourAggregates(aggregates)
            }
        }
    }

    suspend fun backfillHourAggregatesIfNeeded() {
        val settings = settingsStore.settings.first()
        if (settings.hourAggregatesBackfilled) return
        dao.getAllDevices().forEach { device ->
            hourRecomputeMutex(device.id).withLock {
                val samples = dao.getAllSpotSamples(device.id)
                if (samples.isEmpty()) return@withLock
                val zoneId = runCatching { ZoneId.of(device.timezone) }.getOrDefault(ZoneId.systemDefault())
                val aggregates = StatisticsAggregator.hourAggregatesFromSamples(
                    deviceId = device.id,
                    samples = samples,
                    zoneId = zoneId,
                )
                if (aggregates.isNotEmpty()) {
                    val from = aggregates.minOf { it.hourEpochSeconds }
                    val to = aggregates.maxOf { it.hourEpochSeconds }
                    dao.deleteHourAggregatesInRange(device.id, from, to)
                    dao.upsertHourAggregates(aggregates)
                }
            }
        }
        settingsStore.update { it.copy(hourAggregatesBackfilled = true) }
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
        val zoneId = runCatching { ZoneId.of(device.timezone) }.getOrDefault(ZoneId.systemDefault())
        val todayEpochDay = LocalDate.now(zoneId).toEpochDay()
        val todayAggregate = dao.getDayRange(deviceId, todayEpochDay, todayEpochDay).firstOrNull()
        val days = dao.getRecentDayAggregates(deviceId, 32)
        val months = dao.getRecentMonthAggregates(deviceId, 12)
        val tariffs = dao.getTariffs(deviceId)

        val currentMonthKey = YearMonth.now(zoneId).toString()
        val monthYield = months.firstOrNull { it.monthKey == currentMonthKey }?.dayYieldWh
            ?: months.firstOrNull()?.dayYieldWh
        val yearPrefix = YearMonth.now(zoneId).year.toString()
        val yearYield = months
            .filter { it.monthKey.startsWith(yearPrefix) }
            .sumOf { it.dayYieldWh }
        val earnings = days.sumOf { EarningsCalculator.earningsForDay(it, tariffs) }

        return DeviceDashboardSummary(
            deviceId = device.id,
            deviceName = device.name,
            model = device.model,
            currentPowerW = latest?.totalPac,
            todayYieldWh = latest?.eTodayWh ?: todayAggregate?.totalYieldWh,
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
        val device = dao.getDeviceById(deviceId)
        val zoneId = runCatching { ZoneId.of(device?.timezone ?: ZoneId.systemDefault().id) }
            .getOrDefault(ZoneId.systemDefault())
        val end = LocalDate.now(zoneId).toEpochDay()
        val start = LocalDate.now(zoneId).minusDays(days.toLong()).toEpochDay()
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

    suspend fun getHourlySeries(deviceIds: List<Long>, date: LocalDate): List<StatsPoint> {
        if (deviceIds.isEmpty()) return emptyList()
        val tariffsByDevice = tariffsByDevice(deviceIds)
        val locale = Locale.getDefault()
        val labelFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)
        // Query each device in its own timezone day window, then merge by local clock hour.
        data class HourRow(
            val localHour: Int,
            val hourEpochSeconds: Long,
            val yieldWh: Long,
            val maxPowerW: Int?,
            val earnings: Double,
        )
        val rows = mutableListOf<HourRow>()
        for (deviceId in deviceIds) {
            val zoneId = deviceZone(deviceId)
            val start = date.atStartOfDay(zoneId).toEpochSecond()
            val end = date.plusDays(1).atStartOfDay(zoneId).toEpochSecond() - 1
            dao.getHourRange(listOf(deviceId), start, end).forEach { hour ->
                val localHour = Instant.ofEpochSecond(hour.hourEpochSeconds).atZone(zoneId).hour
                rows += HourRow(
                    localHour = localHour,
                    hourEpochSeconds = hour.hourEpochSeconds,
                    yieldWh = hour.yieldWh,
                    maxPowerW = hour.maxPowerW,
                    earnings = EarningsCalculator.earningsForHour(
                        hourEpochSeconds = hour.hourEpochSeconds,
                        yieldWh = hour.yieldWh,
                        tariffs = tariffsByDevice[deviceId].orEmpty(),
                        zoneId = zoneId,
                    ),
                )
            }
        }
        return rows
            .groupBy { it.localHour }
            .toSortedMap()
            .map { (localHour, items) ->
                val label = String.format(locale, "%02d:00", localHour)
                StatsPoint(
                    label = label,
                    bucketKey = localHour.toString(),
                    yieldWh = items.sumOf { it.yieldWh },
                    peakPowerW = items.mapNotNull { it.maxPowerW }.maxOrNull(),
                    earnings = items.sumOf { it.earnings },
                )
            }
    }

    suspend fun getDailySeries(deviceIds: List<Long>, yearMonth: YearMonth): List<StatsPoint> {
        if (deviceIds.isEmpty()) return emptyList()
        val zoneId = primaryZone(deviceIds)
        val start = yearMonth.atDay(1).toEpochDay()
        val end = yearMonth.atEndOfMonth().toEpochDay()
        val days = dao.getDayRangeForDevices(deviceIds, start, end)
        val tariffsByDevice = tariffsByDevice(deviceIds)
        val locale = Locale.getDefault()
        val labelFormatter = DateTimeFormatter.ofPattern("dd", locale)
        return days
            .groupBy { it.dateEpochDay }
            .toSortedMap()
            .map { (epochDay, items) ->
                val yieldWh = items.sumOf { it.totalYieldWh }
                val peak = items.mapNotNull { it.powerW }.maxOrNull()
                val earnings = items.sumOf { day ->
                    EarningsCalculator.earningsForDay(day, tariffsByDevice[day.deviceId].orEmpty())
                }
                StatsPoint(
                    label = LocalDate.ofEpochDay(epochDay).format(labelFormatter),
                    bucketKey = epochDay.toString(),
                    yieldWh = yieldWh,
                    peakPowerW = peak,
                    earnings = earnings,
                )
            }
    }

    suspend fun getMonthlySeries(deviceIds: List<Long>, year: Int): List<StatsPoint> {
        if (deviceIds.isEmpty()) return emptyList()
        val fromKey = "%04d-01".format(year)
        val toKey = "%04d-12".format(year)
        val months = dao.getMonthRangeForDevices(deviceIds, fromKey, toKey)
        val tariffsByDevice = tariffsByDevice(deviceIds)
        val locale = Locale.getDefault()
        return months
            .groupBy { it.monthKey }
            .toSortedMap()
            .map { (monthKey, items) ->
                val yieldWh = items.sumOf { it.dayYieldWh }
                val earnings = items.sumOf { month ->
                    EarningsCalculator.earningsForMonth(month, tariffsByDevice[month.deviceId].orEmpty())
                }
                val yearMonth = YearMonth.parse(monthKey)
                StatsPoint(
                    label = yearMonth.month.getDisplayName(TextStyle.SHORT, locale),
                    bucketKey = monthKey,
                    yieldWh = yieldWh,
                    peakPowerW = null,
                    earnings = earnings,
                )
            }
    }

    suspend fun getYearlySeries(deviceIds: List<Long>): List<StatsPoint> {
        if (deviceIds.isEmpty()) return emptyList()
        val months = dao.getAllMonthsForDevices(deviceIds)
        val tariffsByDevice = tariffsByDevice(deviceIds)
        return months
            .groupBy { it.monthKey.take(4) }
            .toSortedMap()
            .map { (year, items) ->
                val yieldWh = items.sumOf { it.dayYieldWh }
                val earnings = items.sumOf { month ->
                    EarningsCalculator.earningsForMonth(month, tariffsByDevice[month.deviceId].orEmpty())
                }
                StatsPoint(
                    label = year,
                    bucketKey = year,
                    yieldWh = yieldWh,
                    peakPowerW = null,
                    earnings = earnings,
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
        val safeName = relativeName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace("..", "")
            .ifBlank { "import.bin" }
        var file = targetDir.resolve(safeName)
        if (file.exists()) {
            val dot = safeName.lastIndexOf('.')
            val base = if (dot > 0) safeName.substring(0, dot) else safeName
            val ext = if (dot > 0) safeName.substring(dot) else ""
            var index = 1
            do {
                file = targetDir.resolve("$base-$index$ext")
                index++
            } while (file.exists())
        }
        require(file.canonicalPath.startsWith(targetDir.canonicalPath)) {
            "Invalid import path"
        }
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

    private suspend fun deviceZone(deviceId: Long): ZoneId {
        val device = dao.getDeviceById(deviceId)
        return runCatching { ZoneId.of(device?.timezone ?: ZoneId.systemDefault().id) }
            .getOrDefault(ZoneId.systemDefault())
    }

    private suspend fun primaryZone(deviceIds: List<Long>): ZoneId {
        val first = deviceIds.firstOrNull()?.let { dao.getDeviceById(it) }
        return runCatching { ZoneId.of(first?.timezone ?: ZoneId.systemDefault().id) }
            .getOrDefault(ZoneId.systemDefault())
    }

    private suspend fun tariffsByDevice(deviceIds: List<Long>): Map<Long, List<TariffPeriodEntity>> =
        dao.getTariffsForDevices(deviceIds).groupBy { it.deviceId }
}
