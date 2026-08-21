package com.alorbach.solarmonitor.data.repository

import android.content.Context
import androidx.work.WorkManager
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.local.SolarMonitorDatabase
import com.alorbach.solarmonitor.data.importing.ImportCopyStore
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
import com.alorbach.solarmonitor.domain.DashboardMetrics
import com.alorbach.solarmonitor.domain.EarningsCalculator
import com.alorbach.solarmonitor.domain.EventAlertPolicy
import com.alorbach.solarmonitor.domain.StatisticsAggregator
import com.alorbach.solarmonitor.domain.StatsSeriesFill
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
    private val eventAlertNotifier: com.alorbach.solarmonitor.service.EventAlertNotifier? = null,
) {
    companion object {
        const val HOUR_AGGREGATES_SCHEMA_VERSION = 2
    }
    private val dao = db.dao()
    private val hourRecomputeMutexes = ConcurrentHashMap<Long, Mutex>()
    private val importCopyStore = ImportCopyStore(appContext) { deviceId ->
        dao.getDeviceById(deviceId) != null
    }

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
        val id = dao.upsertDevice(encryptPinIfPlain(device.copy(btMac = normalizedMac)))
        return SaveDeviceResult.Success(if (device.id == 0L) id else device.id)
    }

    /** In-memory copy with the plaintext SMA PIN for Bluetooth sessions. */
    fun withResolvedPin(device: DeviceProfileEntity): DeviceProfileEntity {
        val store = credentialStore
        val ref = device.passwordRef
        val pin = when {
            store == null -> ref
            store.isCredentialId(ref) -> store.resolveSmaPin(ref)
            else -> ref
        }
        return device.copy(passwordRef = pin)
    }

    fun displayPin(device: DeviceProfileEntity): String {
        val store = credentialStore ?: return CredentialStore.pinForDisplay(device.passwordRef, null)
        return store.pinForDisplay(device.passwordRef)
    }

    suspend fun saveEditedDevice(device: DeviceProfileEntity, plainPin: String): Boolean {
        val previousTimezone = dao.getDeviceById(device.id)?.timezone
        val normalizedMac = device.btMac?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        if (normalizedMac != null) {
            val existing = dao.getDeviceByMac(normalizedMac)
            if (existing != null && existing.id != device.id) {
                return false
            }
        }
        val store = credentialStore
        val withPin = if (store == null) {
            device.copy(passwordRef = plainPin)
        } else {
            device.copy(passwordRef = store.persistSmaPin(plainPin, device.passwordRef))
        }
        if (saveDevice(withPin) !is SaveDeviceResult.Success) return false
        if (previousTimezone != null && previousTimezone != device.timezone) {
            recomputeHourAggregatesForDevice(device.id)
        }
        return true
    }

    private suspend fun recomputeHourAggregatesForDevice(deviceId: Long) {
        dao.deleteHourAggregatesForDevice(deviceId)
        val samples = dao.getAllSpotSamples(deviceId)
        if (samples.isEmpty()) return
        recomputeHourAggregates(
            deviceId,
            samples.minOf { it.timestampEpochSeconds },
            samples.maxOf { it.timestampEpochSeconds },
        )
    }

    suspend fun migrateLegacyDevicePins() {
        val store = credentialStore ?: return
        dao.getAllDevices().forEach { device ->
            val ref = device.passwordRef ?: return@forEach
            if (store.isCredentialId(ref)) return@forEach
            dao.updateDevice(device.copy(passwordRef = store.persistSmaPin(ref, existingRef = null)))
        }
    }

    private fun encryptPinIfPlain(device: DeviceProfileEntity): DeviceProfileEntity {
        val store = credentialStore ?: return device
        val ref = device.passwordRef?.trim().orEmpty()
        if (ref.isEmpty() || store.isCredentialId(ref)) return device
        return device.copy(passwordRef = store.persistSmaPin(ref, existingRef = null))
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

    suspend fun deleteDevice(deviceId: Long) {
        val jobs = dao.getImportJobsForDevice(deviceId)
        val credentialIds = jobs.mapNotNull { it.passwordCredentialId }
            .filter { it.isNotBlank() }
            .toSet()
        val device = dao.getDeviceById(deviceId)
        importCopyStore.deleteForDevice(
            deviceId = deviceId,
            legacyPaths = jobs.mapNotNull { it.preservedCopyPath },
        ) {
            // Do this first: a failed cleanup must not leave the surviving device with
            // its scheduled import work already cancelled.
            ScheduledImportWorker.cancelAll(appContext, jobs.map { it.id })
            WorkManager.getInstance(appContext)
                .cancelAllWorkByTag(ScheduledImportWorker.deviceTag(deviceId))
            dao.deleteDeviceWithImportJobs(deviceId)
            reclaimOrphanImportCredentials(credentialIds, ignoreWork = true)
            val ref = device?.passwordRef
            if (credentialStore?.isCredentialId(ref) == true) {
                credentialStore.deleteSecret(ref)
            }
            settingsStore.update { current ->
                current.copy(
                    widgetDeviceId = current.widgetDeviceId?.takeUnless { it == deviceId },
                    statsSelectedDeviceId = current.statsSelectedDeviceId?.takeUnless { it == deviceId },
                    eventAlertWatermarks = EventAlertPolicy.encodeWatermarks(
                        EventAlertPolicy.parseWatermarks(current.eventAlertWatermarks) - deviceId,
                    ),
                )
            }
        }
    }

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
        if (items.isNotEmpty()) dao.mergeDayAggregates(items)
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
        if (dayItems.isNotEmpty()) dao.mergeDayAggregates(dayItems)
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
        if (items.isEmpty()) return
        dao.upsertEvents(items)
        considerEventAlerts(items)
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

    suspend fun considerEventAlerts(events: List<DeviceEventEntity>) {
        if (events.isEmpty()) return
        val notifier = eventAlertNotifier ?: return
        val names = events.map { it.deviceId }.distinct().associateWith { id ->
            dao.getDeviceById(id)?.name
        }
        runCatching {
            notifier.onEventsSaved(events) { deviceId -> names[deviceId] }
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
            ).filter { it.hourEpochSeconds in lookback..toHour }
            dao.replaceHourAggregatesInRange(deviceId, lookback, toHour, aggregates)
        }
    }

    suspend fun backfillHourAggregatesIfNeeded() {
        val settings = settingsStore.settings.first()
        if (settings.hourAggregatesSchemaVersion >= HOUR_AGGREGATES_SCHEMA_VERSION) return
        dao.getAllDevices().forEach { device ->
            hourRecomputeMutex(device.id).withLock {
                val samples = dao.getAllSpotSamples(device.id)
                val zoneId = runCatching { ZoneId.of(device.timezone) }.getOrDefault(ZoneId.systemDefault())
                val aggregates = if (samples.isEmpty()) {
                    emptyList()
                } else {
                    StatisticsAggregator.hourAggregatesFromSamples(
                        deviceId = device.id,
                        samples = samples,
                        zoneId = zoneId,
                    )
                }
                dao.replaceHourAggregatesForDevice(device.id, aggregates)
            }
        }
        settingsStore.update {
            it.copy(
                hourAggregatesBackfilled = true,
                hourAggregatesSchemaVersion = HOUR_AGGREGATES_SCHEMA_VERSION,
            )
        }
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
        message = appContext.getString(R.string.import_interrupted),
        createdBeforeEpochSeconds = processStartedAtEpochSeconds,
    )

    suspend fun deleteImportJob(jobId: Long) {
        ScheduledImportWorker.cancel(appContext, jobId)
        val credentialId = dao.getImportJob(jobId)?.passwordCredentialId
        dao.deleteImportJob(jobId)
        if (!credentialId.isNullOrBlank()) {
            reclaimOrphanImportCredentials(setOf(credentialId), ignoreWork = true)
        }
    }

    suspend fun deleteAllImportJobs() {
        ScheduledImportWorker.cancelAll(appContext, dao.listImportJobIds())
        val credentialIds = dao.listImportPasswordCredentialIds()
            .filter { it.isNotBlank() }
            .toSet()
        dao.deleteAllImportJobs()
        reclaimOrphanImportCredentials(credentialIds, ignoreWork = true)
    }

    /**
     * Drop encrypted import passwords only when no history row and no tagged WorkManager
     * job still references them. Untagged scheduled work cannot be inspected for input
     * credential IDs; those secrets stay until jobs/tags clear.
     */
    suspend fun reclaimOrphanImportCredential(credentialId: String) {
        reclaimOrphanImportCredentials(setOf(credentialId))
    }

    private suspend fun reclaimOrphanImportCredentials(
        candidates: Set<String>,
        ignoreWork: Boolean = false,
    ) {
        val store = credentialStore ?: return
        for (id in candidates) {
            if (dao.countImportJobsWithCredential(id) > 0) continue
            if (!ignoreWork && isCredentialReferencedByWork(id)) continue
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
        val monthYield = DashboardMetrics.monthYieldWh(currentMonthKey, months)
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
            currentPowerW = DashboardMetrics.currentPowerW(
                latestPac = latest?.totalPac,
                sampleEpochSeconds = latest?.timestampEpochSeconds
                    ?: device.lastLiveReadAtEpochSeconds,
                nowEpochSeconds = System.currentTimeMillis() / 1000,
            ),
            todayYieldWh = DashboardMetrics.todayYieldWh(
                latestETodayWh = latest?.eTodayWh,
                sampleEpochSeconds = latest?.timestampEpochSeconds,
                todayEpochDay = todayEpochDay,
                zoneId = zoneId,
                dayAggregateYieldWh = todayAggregate?.totalYieldWh,
            ),
            monthYieldWh = monthYield,
            yearlyYieldWh = yearYield,
            estimatedEarnings = earnings,
            currency = tariffs.firstOrNull()?.currency,
            status = device.lastConnectionStatus ?: latest?.status,
            lastUpdateEpochSeconds = device.lastLiveReadAtEpochSeconds ?: latest?.timestampEpochSeconds,
            temperatureC = latest?.temperatureC,
            frequencyHz = latest?.frequencyHz,
            pdc1 = latest?.pdc1,
            pdc2 = latest?.pdc2,
            pac1 = latest?.pac1,
            pac2 = latest?.pac2,
            pac3 = latest?.pac3,
            gridRelay = latest?.gridRelay,
            btSignalPercent = latest?.btSignalPercent,
            serial = device.serial,
        )
    }

    suspend fun getPortfolioSummary(): PortfolioSummary {
        val devices = dao.getAllDevices()
        val summaries = devices.mapNotNull { getDeviceDashboard(it.id) }
        return PortfolioSummary(
            deviceCount = summaries.size,
            currentPowerW = summaries.mapNotNull { it.currentPowerW }.takeIf { it.isNotEmpty() }?.sum(),
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
        val byDay = dao.getDayRange(deviceId, start, end).associateBy { it.dateEpochDay }
        if (byDay.isEmpty()) return emptyList()
        return (start..end).map { epochDay ->
            val row = byDay[epochDay]
            DailyPoint(
                dateEpochDay = epochDay,
                yieldWh = row?.totalYieldWh ?: 0L,
                earnings = row?.let { EarningsCalculator.earningsForDay(it, tariffs) } ?: 0.0,
            )
        }
    }

    suspend fun getMonthlyChart(deviceId: Long): List<MonthlyPoint> {
        val tariffs = dao.getTariffs(deviceId)
        val months = dao.getAllMonths(deviceId)
        if (months.isEmpty()) return emptyList()
        val startDay = months.minOf { java.time.YearMonth.parse(it.monthKey).atDay(1).toEpochDay() }
        val endDay = months.maxOf { java.time.YearMonth.parse(it.monthKey).atEndOfMonth().toEpochDay() }
        val days = dao.getDayRange(deviceId, startDay, endDay)
        return months.map {
            MonthlyPoint(
                monthKey = it.monthKey,
                yieldWh = it.dayYieldWh,
                earnings = EarningsCalculator.earningsForMonth(it, tariffs, days),
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
        val byHour = rows.groupBy { it.localHour }
        val eventCounts = eventCountsByHour(deviceIds, date)
        if (byHour.isEmpty() && eventCounts.values.none { it > 0 }) return emptyList()
        return (0..23).map { localHour ->
            val items = byHour[localHour].orEmpty()
            StatsPoint(
                label = String.format(locale, "%02d:00", localHour),
                bucketKey = localHour.toString(),
                yieldWh = items.sumOf { it.yieldWh },
                peakPowerW = items.mapNotNull { it.maxPowerW }.maxOrNull(),
                earnings = items.sumOf { it.earnings },
                eventCount = eventCounts[localHour] ?: 0,
            )
        }.let { hours ->
            val first = hours.indexOfFirst { it.yieldWh > 0L || it.eventCount > 0 }
            val last = hours.indexOfLast { it.yieldWh > 0L || it.eventCount > 0 }
            if (first < 0 || last < first) emptyList() else hours.subList(first, last + 1)
        }
    }

    suspend fun getHourlySeriesToday(deviceIds: List<Long>): List<StatsPoint> {
        if (deviceIds.isEmpty()) return emptyList()
        val merged = LinkedHashMap<Int, StatsPoint>()
        for (id in deviceIds) {
            val zone = deviceZone(id)
            for (point in getHourlySeries(listOf(id), LocalDate.now(zone))) {
                val hour = point.bucketKey.toIntOrNull() ?: continue
                val existing = merged[hour]
                merged[hour] = if (existing == null) {
                    point
                } else {
                    existing.copy(
                        yieldWh = existing.yieldWh + point.yieldWh,
                        peakPowerW = listOfNotNull(existing.peakPowerW, point.peakPowerW).maxOrNull(),
                        earnings = existing.earnings + point.earnings,
                        eventCount = existing.eventCount + point.eventCount,
                    )
                }
            }
        }
        if (merged.isEmpty()) return emptyList()
        return merged.keys.sorted().map { merged.getValue(it) }
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
        val byDay = days.groupBy { it.dateEpochDay }
        val lastDay = StatsSeriesFill.lastInclusiveEpochDay(yearMonth, LocalDate.now(zoneId))
        val eventCounts = eventCountsByEpochDay(deviceIds, yearMonth, lastDay)
        if (byDay.isEmpty() && eventCounts.values.none { it > 0 }) return emptyList()
        return (start..lastDay).map { epochDay ->
            val items = byDay[epochDay].orEmpty()
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
                eventCount = eventCounts[epochDay] ?: 0,
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
        val eventCounts = eventCountsByMonthKey(deviceIds, year)
        val byMonth = months.groupBy { it.monthKey }
        val keys = (byMonth.keys + eventCounts.filterValues { it > 0 }.keys).toSortedSet()
        if (keys.isEmpty()) return emptyList()
        val startDay = java.time.YearMonth.of(year, 1).atDay(1).toEpochDay()
        val endDay = java.time.YearMonth.of(year, 12).atEndOfMonth().toEpochDay()
        val daysByDevice = dao.getDayRangeForDevices(deviceIds, startDay, endDay).groupBy { it.deviceId }
        return keys.map { monthKey ->
            val items = byMonth[monthKey].orEmpty()
            val yieldWh = items.sumOf { it.dayYieldWh }
            val earnings = items.sumOf { month ->
                EarningsCalculator.earningsForMonth(
                    month,
                    tariffsByDevice[month.deviceId].orEmpty(),
                    daysByDevice[month.deviceId].orEmpty(),
                )
            }
            val yearMonth = YearMonth.parse(monthKey)
            StatsPoint(
                label = yearMonth.month.getDisplayName(TextStyle.SHORT, locale),
                bucketKey = monthKey,
                yieldWh = yieldWh,
                peakPowerW = null,
                earnings = earnings,
                eventCount = eventCounts[monthKey] ?: 0,
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
            months.map { it.monthKey.take(4) } + dayYears.map { it.yearKey } + eventYearsForDevices(deviceIds)
            ).toSortedSet()
        if (years.isEmpty()) return emptyList()
        val eventCounts = eventCountsByYearKey(deviceIds, years)
        // Only load day rows for device/year pairs that actually use day fallback + tariffs.
        val dayFallbackKeys = buildList {
            for (deviceId in deviceIds) {
                if (tariffsByDevice[deviceId].orEmpty().isEmpty()) continue
                for (year in years) {
                    add(deviceId to year)
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
                        EarningsCalculator.earningsForMonth(
                            month,
                            tariffsByDevice[deviceId].orEmpty(),
                            earningsDaysByDeviceYear[key].orEmpty(),
                        )
                    }
                }
            }
            StatsPoint(
                label = year,
                bucketKey = year,
                yieldWh = yieldWh,
                peakPowerW = peak,
                earnings = earnings,
                eventCount = eventCounts[year] ?: 0,
            )
        }.filter { it.yieldWh > 0L || it.peakPowerW != null || it.earnings != 0.0 || it.eventCount > 0 }
    }

    suspend fun getRecentEvents(deviceId: Long, limit: Int = 25): List<DeviceEventEntity> =
        dao.getRecentEvents(deviceId, limit)

    suspend fun getEventsForRange(
        deviceIds: List<Long>,
        fromEpochSeconds: Long,
        toEpochSeconds: Long,
        limit: Int = 150,
    ): List<DeviceEventEntity> {
        if (deviceIds.isEmpty()) return emptyList()
        return dao.getEventsForRange(deviceIds, fromEpochSeconds, toEpochSeconds, limit)
    }

    suspend fun getEventsForLocalWindows(
        deviceIds: List<Long>,
        limit: Int = 150,
        windowForZone: (ZoneId) -> Pair<Long, Long>,
    ): List<DeviceEventEntity> {
        if (deviceIds.isEmpty()) return emptyList()
        if (deviceIds.size == 1) {
            val zoneId = deviceZone(deviceIds.first())
            val (fromEpoch, toEpoch) = windowForZone(zoneId)
            return dao.getEventsForRange(deviceIds, fromEpoch, toEpoch, limit)
        }
        return deviceIds.flatMap { deviceId ->
            val zoneId = deviceZone(deviceId)
            val (fromEpoch, toEpoch) = windowForZone(zoneId)
            dao.getEventsForRange(listOf(deviceId), fromEpoch, toEpoch, limit)
        }.sortedByDescending { it.timestampEpochSeconds }.take(limit)
    }

    suspend fun currencyForDevices(deviceIds: List<Long>): String? {
        if (deviceIds.isEmpty()) return null
        return dao.getTariffsForDevices(deviceIds).firstOrNull()?.currency
    }

    private suspend fun eventYearsForDevices(deviceIds: List<Long>): Set<String> {
        val minTs = dao.getMinEventTimestamp(deviceIds) ?: return emptySet()
        val maxTs = dao.getMaxEventTimestamp(deviceIds) ?: return emptySet()
        val years = mutableSetOf<String>()
        for (deviceId in deviceIds) {
            val zoneId = deviceZone(deviceId)
            val minYear = Instant.ofEpochSecond(minTs).atZone(zoneId).year
            val maxYear = Instant.ofEpochSecond(maxTs).atZone(zoneId).year
            for (year in minYear..maxYear) {
                years += year.toString()
            }
        }
        return years
    }

    private suspend fun eventTimestamps(deviceIds: List<Long>, fromEpoch: Long, toEpoch: Long): List<Long> {
        if (deviceIds.isEmpty()) return emptyList()
        return dao.getEventTimestamps(deviceIds, fromEpoch, toEpoch)
    }

    private suspend fun eventCountsByHour(
        deviceIds: List<Long>,
        date: LocalDate,
    ): Map<Int, Int> {
        val counts = mutableMapOf<Int, Int>()
        for (deviceId in deviceIds) {
            val zoneId = deviceZone(deviceId)
            val fromEpoch = date.atStartOfDay(zoneId).toEpochSecond()
            val toEpoch = date.plusDays(1).atStartOfDay(zoneId).toEpochSecond() - 1
            eventTimestamps(listOf(deviceId), fromEpoch, toEpoch).forEach { timestamp ->
                val hour = Instant.ofEpochSecond(timestamp).atZone(zoneId).hour
                counts[hour] = (counts[hour] ?: 0) + 1
            }
        }
        return counts
    }

    private suspend fun eventCountsByEpochDay(
        deviceIds: List<Long>,
        yearMonth: YearMonth,
        lastInclusiveEpochDay: Long,
    ): Map<Long, Int> {
        val counts = mutableMapOf<Long, Int>()
        for (deviceId in deviceIds) {
            val zoneId = deviceZone(deviceId)
            val fromEpoch = yearMonth.atDay(1).atStartOfDay(zoneId).toEpochSecond()
            val toEpoch = LocalDate.ofEpochDay(lastInclusiveEpochDay).plusDays(1)
                .atStartOfDay(zoneId).toEpochSecond() - 1
            eventTimestamps(listOf(deviceId), fromEpoch, toEpoch).forEach { timestamp ->
                val epochDay = Instant.ofEpochSecond(timestamp).atZone(zoneId).toLocalDate().toEpochDay()
                counts[epochDay] = (counts[epochDay] ?: 0) + 1
            }
        }
        return counts
    }

    private suspend fun eventCountsByMonthKey(
        deviceIds: List<Long>,
        year: Int,
    ): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (deviceId in deviceIds) {
            val zoneId = deviceZone(deviceId)
            val fromEpoch = LocalDate.of(year, 1, 1).atStartOfDay(zoneId).toEpochSecond()
            val toEpoch = LocalDate.of(year + 1, 1, 1).atStartOfDay(zoneId).toEpochSecond() - 1
            eventTimestamps(listOf(deviceId), fromEpoch, toEpoch).forEach { timestamp ->
                val date = Instant.ofEpochSecond(timestamp).atZone(zoneId).toLocalDate()
                val key = "%04d-%02d".format(date.year, date.monthValue)
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts
    }

    private suspend fun eventCountsByYearKey(
        deviceIds: List<Long>,
        years: Set<String>,
    ): Map<String, Int> {
        val minYear = years.minOrNull()?.toIntOrNull() ?: return emptyMap()
        val maxYear = years.maxOrNull()?.toIntOrNull() ?: return emptyMap()
        val counts = mutableMapOf<String, Int>()
        for (deviceId in deviceIds) {
            val zoneId = deviceZone(deviceId)
            val fromEpoch = LocalDate.of(minYear, 1, 1).atStartOfDay(zoneId).toEpochSecond()
            val toEpoch = LocalDate.of(maxYear + 1, 1, 1).atStartOfDay(zoneId).toEpochSecond() - 1
            eventTimestamps(listOf(deviceId), fromEpoch, toEpoch).forEach { timestamp ->
                val year = Instant.ofEpochSecond(timestamp).atZone(zoneId).year.toString()
                counts[year] = (counts[year] ?: 0) + 1
            }
        }
        return counts
    }

    suspend fun getRecentSpotSamples(deviceId: Long, limit: Int = 60): List<SpotSampleEntity> =
        dao.getRecentSpotSamples(deviceId, limit)

    fun observeCloudBackupEnabled(): Flow<Boolean> = settingsStore.settings.map { it.cloudBackupEnabled }

    suspend fun storeImportedCopy(
        deviceId: Long,
        relativeName: String,
        bytes: ByteArray,
        overwritePath: String? = null,
    ): String = importCopyStore.store(deviceId, relativeName, bytes, overwritePath)

    suspend fun updateDeviceStatus(
        deviceId: Long,
        status: String,
        liveAtEpochSeconds: Long? = null,
        archiveAtEpochSeconds: Long? = null,
        socketStrategy: String? = null,
        diagnostics: String? = null,
        serial: Long? = null,
    ) {
        val device = dao.getDeviceById(deviceId) ?: return
        dao.updateDevice(
            device.copy(
                lastConnectionStatus = status,
                lastSuccessfulSocketStrategy = socketStrategy ?: device.lastSuccessfulSocketStrategy,
                lastDiagnostics = diagnostics ?: device.lastDiagnostics,
                lastLiveReadAtEpochSeconds = liveAtEpochSeconds ?: device.lastLiveReadAtEpochSeconds,
                lastArchiveSyncAtEpochSeconds = archiveAtEpochSeconds ?: device.lastArchiveSyncAtEpochSeconds,
                serial = serial?.takeIf { it > 0L } ?: device.serial,
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
