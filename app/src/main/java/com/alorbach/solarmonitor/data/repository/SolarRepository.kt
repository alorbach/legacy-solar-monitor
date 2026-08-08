package com.alorbach.solarmonitor.data.repository

import android.content.Context
import androidx.work.WorkManager
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
import com.alorbach.solarmonitor.data.security.CredentialStore
import com.alorbach.solarmonitor.domain.EarningsCalculator
import com.alorbach.solarmonitor.domain.StatisticsAggregator
import com.alorbach.solarmonitor.work.ScheduledImportWorker
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SolarRepository(
    private val appContext: Context,
    private val db: SolarMonitorDatabase,
    private val settingsStore: AppSettingsStore,
    private val credentialStore: CredentialStore? = null,
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

    /** Wipes all stored history for a device. Sync/import never call this — user must opt in. */
    suspend fun clearDeviceHistory(deviceId: Long) {
        hourRecomputeMutex(deviceId).withLock {
            dao.clearDeviceHistory(deviceId)
        }
    }

    suspend fun getDevice(deviceId: Long): DeviceProfileEntity? = dao.getDeviceById(deviceId)

    suspend fun getDeviceByMac(mac: String): DeviceProfileEntity? = dao.getDeviceByMac(mac)

    suspend fun saveTariffs(deviceId: Long, tariffs: List<TariffPeriodEntity>) {
        dao.replaceTariffs(deviceId, tariffs.map { it.copy(deviceId = deviceId) })
    }

    suspend fun getTariffs(deviceId: Long): List<TariffPeriodEntity> = dao.getTariffs(deviceId)

    suspend fun saveImportSource(source: ImportSourceEntity): Long = dao.upsertImportSource(source)

    suspend fun getImportSources(deviceId: Long): List<ImportSourceEntity> = dao.getImportSources(deviceId)

    suspend fun saveSpotSamples(samples: List<SpotSampleEntity>) {
        if (samples.isNotEmpty()) dao.upsertSpotSamples(samples)
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
        if (spotSamples.isNotEmpty()) dao.upsertSpotSamples(spotSamples)
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
        recomputeHours: Boolean = true,
    ) {
        val deviceId = spotSamples.firstOrNull()?.deviceId
            ?: dayAggregates.firstOrNull()?.deviceId
            ?: monthAggregates.firstOrNull()?.deviceId
            ?: events.firstOrNull()?.deviceId
        if (deviceId != null) {
            hourRecomputeMutex(deviceId).withLock {
                dao.importBundle(spotSamples, dayAggregates, monthAggregates, events)
            }
        } else {
            dao.importBundle(spotSamples, dayAggregates, monthAggregates, events)
        }
        if (!recomputeHours) return
        spotSamples.groupBy { it.deviceId }.forEach { (id, samples) ->
            if (samples.isNotEmpty()) {
                recomputeHourAggregates(
                    deviceId = id,
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
        // Bound heap for multi-year folder imports: recompute one week at a time.
        val chunkSeconds = 7L * 24L * 3600L
        var cursor = fromEpochSeconds
        while (cursor <= toEpochSeconds) {
            val chunkEnd = minOf(toEpochSeconds, cursor + chunkSeconds - 1)
            recomputeHourAggregatesWindow(deviceId, cursor, chunkEnd)
            cursor = chunkEnd + 1
        }
    }

    private suspend fun recomputeHourAggregatesWindow(
        deviceId: Long,
        fromEpochSeconds: Long,
        toEpochSeconds: Long,
    ) {
        hourRecomputeMutex(deviceId).withLock {
            val zoneId = deviceZone(deviceId)
            val fromHour = StatisticsAggregator.hourStartEpochSeconds(fromEpochSeconds, zoneId)
            val toHour = StatisticsAggregator.hourStartEpochSeconds(toEpochSeconds, zoneId)
            // Short live windows keep a 1h baseline; multi-day folder chunks keep a week
            // so cumulative eTotalWh survives gaps at chunk boundaries.
            val spanSeconds = (toEpochSeconds - fromEpochSeconds).coerceAtLeast(0L)
            val lookbackPad = if (spanSeconds <= 6L * 3600L) 3600L else 7L * 24L * 3600L
            val lookback = fromHour - lookbackPad
            val samples = dao.getSpotSamplesInRange(deviceId, lookback, toHour + 3599)
            val aggregates = StatisticsAggregator.hourAggregatesFromSamples(
                deviceId = deviceId,
                samples = samples,
                zoneId = zoneId,
            ).filter { it.hourEpochSeconds in fromHour..toHour }
            // Replace the recomputed window so hours without usable eTotalWh do not stay stale.
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
                    dao.upsertHourAggregates(aggregates)
                }
            }
        }
        settingsStore.update { it.copy(hourAggregatesBackfilled = true) }
    }

    suspend fun recordImportJob(job: ImportJobEntity): Long = dao.insertImportJob(job)

    suspend fun setImportJobCredential(jobId: Long, credentialId: String?) {
        dao.setImportJobCredential(jobId, credentialId)
    }

    suspend fun completeImportJob(jobId: Long, success: Boolean, message: String?, copyPath: String?) {
        dao.completeImportJob(
            jobId = jobId,
            status = if (success) "SUCCEEDED" else "FAILED",
            completedAt = System.currentTimeMillis() / 1000,
            message = message,
            copyPath = copyPath,
        )
    }

    /**
     * Marks leftover RUNNING jobs failed after process death / abandoned UI imports.
     * Only jobs created before [processStartedAtEpochSeconds] are touched so a
     * ScheduledImportWorker started in this process is not raced.
     */
    suspend fun failOrphanedImportJobs(processStartedAtEpochSeconds: Long): Int = dao.failRunningImportJobs(
        completedAt = System.currentTimeMillis() / 1000,
        message = "Import interrupted (app closed or process stopped)",
        createdBeforeEpochSeconds = processStartedAtEpochSeconds,
    )

    suspend fun deleteImportJob(jobId: Long) {
        val credentialId = dao.getImportJob(jobId)?.passwordCredentialId
        dao.deleteImportJob(jobId)
        if (!credentialId.isNullOrBlank()) {
            reclaimOrphanImportCredentials(setOf(credentialId))
        }
    }

    suspend fun deleteAllImportJobs() {
        val credentialIds = dao.listImportPasswordCredentialIds()
            .filter { it.isNotBlank() }
            .toSet()
        dao.deleteAllImportJobs()
        reclaimOrphanImportCredentials(credentialIds)
    }

    /**
     * Drop encrypted import passwords only when no history row and no tagged WorkManager
     * job still references them. Untagged scheduled work cannot be inspected for input
     * credential IDs; those secrets stay until jobs/tags clear.
     */
    suspend fun reclaimOrphanImportCredential(credentialId: String) {
        reclaimOrphanImportCredentials(setOf(credentialId))
    }

    private suspend fun reclaimOrphanImportCredentials(candidates: Set<String>) {
        val store = credentialStore ?: return
        for (id in candidates) {
            if (dao.countImportJobsWithCredential(id) > 0) continue
            if (isCredentialReferencedByWork(id)) continue
            store.deleteSecret(id)
        }
    }

    private suspend fun isCredentialReferencedByWork(credentialId: String): Boolean =
        withContext(Dispatchers.IO) {
            val tagged = WorkManager.getInstance(appContext)
                .getWorkInfosByTag(ScheduledImportWorker.credentialTag(credentialId))
                .get()
            tagged.any { !it.state.isFinished }
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
        val year = YearMonth.now(zoneId).year
        val yearStart = LocalDate.of(year, 1, 1).toEpochDay()
        val yearEnd = LocalDate.of(year, 12, 31).toEpochDay()
        val monthYearYield = months
            .filter { it.monthKey.startsWith(yearPrefix) }
            .sumOf { it.dayYieldWh }
        val dayYearYield = dao.sumDayYieldWh(deviceId, yearStart, yearEnd)
        val yearYield = when {
            dayYearYield > 0L && dayYearYield >= monthYearYield -> dayYearYield
            monthYearYield > 0L -> monthYearYield
            else -> null
        }
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
        val dayYears = dao.getYearlyDayYieldsForDevices(deviceIds)
        val tariffsByDevice = tariffsByDevice(deviceIds)
        val monthsByDeviceYear = months.groupBy { it.deviceId to it.monthKey.take(4) }
        val daysByDeviceYear = dayYears.associateBy { it.deviceId to it.yearKey }
        val years = (
            months.map { it.monthKey.take(4) } + dayYears.map { it.yearKey }
            ).toSortedSet()
        // Only load day rows for device/year pairs that actually use day fallback + tariffs.
        val dayFallbackKeys = buildList {
            for (deviceId in deviceIds) {
                if (tariffsByDevice[deviceId].orEmpty().isEmpty()) continue
                for (year in years) {
                    val key = deviceId to year
                    val monthYield = monthsByDeviceYear[key].orEmpty().sumOf { it.dayYieldWh }
                    val dayRow = daysByDeviceYear[key]
                    val dayYield = dayRow?.yieldWh ?: 0L
                    if (dayRow != null && dayYield >= monthYield && dayYield > 0L) {
                        add(key)
                    }
                }
            }
        }
        val daysForEarnings = if (dayFallbackKeys.isEmpty()) {
            emptyList()
        } else {
            dayFallbackKeys
                .groupBy({ it.second }) { it.first }
                .flatMap { (yearKey, ids) ->
                    val year = yearKey.toInt()
                    val startDay = LocalDate.of(year, 1, 1).toEpochDay()
                    val endDay = LocalDate.of(year, 12, 31).toEpochDay()
                    dao.getDayRangeForDevices(ids.distinct(), startDay, endDay)
                }
        }
        val earningsDaysByDeviceYear = daysForEarnings.groupBy {
            it.deviceId to LocalDate.ofEpochDay(it.dateEpochDay).year.toString()
        }
        return years.map { year ->
            var yieldWh = 0L
            var earnings = 0.0
            var peak: Int? = null
            for (deviceId in deviceIds) {
                val key = deviceId to year
                val monthItems = monthsByDeviceYear[key].orEmpty()
                val dayRow = daysByDeviceYear[key]
                val monthYield = monthItems.sumOf { it.dayYieldWh }
                val dayYield = dayRow?.yieldWh ?: 0L
                val useDays = dayRow != null && dayYield >= monthYield && dayYield > 0L
                if (useDays) {
                    yieldWh += dayYield
                    peak = listOfNotNull(peak, dayRow.peakPowerW).maxOrNull()
                    val tariffs = tariffsByDevice[deviceId].orEmpty()
                    if (tariffs.isNotEmpty()) {
                        earnings += earningsDaysByDeviceYear[key].orEmpty().sumOf { day ->
                            EarningsCalculator.earningsForDay(day, tariffs)
                        }
                    }
                } else if (monthItems.isNotEmpty()) {
                    yieldWh += monthYield
                    earnings += monthItems.sumOf { month ->
                        EarningsCalculator.earningsForMonth(month, tariffsByDevice[deviceId].orEmpty())
                    }
                }
            }
            StatsPoint(
                label = year,
                bucketKey = year,
                yieldWh = yieldWh,
                peakPowerW = peak,
                earnings = earnings,
            )
        }.filter { it.yieldWh > 0L || it.peakPowerW != null || it.earnings != 0.0 }
    }

    suspend fun getRecentEvents(deviceId: Long, limit: Int = 25): List<DeviceEventEntity> =
        dao.getRecentEvents(deviceId, limit)

    suspend fun getRecentSpotSamples(deviceId: Long, limit: Int = 60): List<SpotSampleEntity> =
        dao.getRecentSpotSamples(deviceId, limit)

    fun observeCloudBackupEnabled(): Flow<Boolean> = settingsStore.settings.map { it.cloudBackupEnabled }

    suspend fun storeImportedCopy(
        deviceId: Long,
        relativeName: String,
        bytes: ByteArray,
        overwritePath: String? = null,
    ): String {
        val importsRoot = appContext.getDir("imports", Context.MODE_PRIVATE)
        val targetDir = importsRoot.resolve("device-$deviceId")
        targetDir.mkdirs()
        if (!overwritePath.isNullOrBlank()) {
            val overwrite = java.io.File(overwritePath)
            require(overwrite.isInside(importsRoot)) {
                "Invalid import path"
            }
            // Legacy copies lived directly under imports/; migrate into device-<id>/.
            // Use Path component checks so device-10 is not treated as inside device-1.
            val target = if (overwrite.isInside(targetDir)) {
                overwrite
            } else {
                targetDir.resolve(overwrite.name)
            }
            require(target.isInside(targetDir)) {
                "Invalid import path"
            }
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            if (target.canonicalPath != overwrite.canonicalPath && overwrite.exists()) {
                runCatching { overwrite.delete() }
            }
            return target.absolutePath
        }
        val safeName = relativeName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace("..", "")
            .ifBlank { "import.bin" }
        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val ext = if (dot > 0) safeName.substring(dot) else ""
        var candidate = targetDir.resolve(safeName)
        var suffix = 1
        while (candidate.exists()) {
            candidate = targetDir.resolve("$base-$suffix$ext")
            suffix++
        }
        require(candidate.isInside(targetDir)) {
            "Invalid import path"
        }
        candidate.writeBytes(bytes)
        return candidate.absolutePath
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

/** True when [this] is [directory] or a descendant; component-aware (not raw string prefix). */
private fun java.io.File.isInside(directory: java.io.File): Boolean {
    val dirPath = directory.canonicalFile.toPath()
    val filePath = canonicalFile.toPath()
    return filePath.startsWith(dirPath)
}
