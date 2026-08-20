package com.alorbach.solarmonitor.data.repository

import android.content.Context
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.cloud.BackupTrigger
import com.alorbach.solarmonitor.data.cloud.CloudBackupCoordinator
import com.alorbach.solarmonitor.data.model.DeviceProfileEntity
import com.alorbach.solarmonitor.data.model.SpotSampleEntity
import com.alorbach.solarmonitor.device.SmaLegacyBluetoothGateway
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DeviceLiveState(
    val deviceId: Long,
    val active: Boolean = false,
    val message: String = "",
    val latest: SpotSampleEntity? = null,
    val connected: Boolean = false,
)

data class LiveMonitoringState(
    val devices: Map<Long, DeviceLiveState> = emptyMap(),
    val activeDeviceIds: Set<Long> = emptySet(),
    val idleLabel: String = "",
) {
    val active: Boolean get() = activeDeviceIds.isNotEmpty()
    val deviceId: Long? get() = activeDeviceIds.firstOrNull() ?: devices.keys.firstOrNull()
    val message: String
        get() {
            if (activeDeviceIds.isEmpty()) {
                return devices.values.lastOrNull()?.message?.takeIf { it.isNotBlank() } ?: idleLabel
            }
            val activeStates = activeDeviceIds.mapNotNull { devices[it] }
            val connected = activeStates.count { it.connected }
            return if (activeDeviceIds.size == 1) {
                activeStates.firstOrNull()?.message ?: idleLabel
            } else {
                // Keep "total / connected" formatting; values are digits only.
                "${activeDeviceIds.size} / $connected"
            }
        }
    val latest: SpotSampleEntity? get() = devices[deviceId]?.latest
}

class LiveMonitoringRepository(
    private val appContext: Context,
    private val repository: SolarRepository,
    private val bluetoothGateway: SmaLegacyBluetoothGateway,
    private val cloudBackupCoordinator: CloudBackupCoordinator,
) {
    private val _state = MutableStateFlow(LiveMonitoringState(idleLabel = appContext.getString(R.string.idle)))
    val state: StateFlow<LiveMonitoringState> = _state.asStateFlow()

    /**
     * One tracked Bluetooth operation. Operations on different devices may overlap, so the abort
     * outcome lives on the operation itself: neither a later operation nor another device can clear
     * it before the aborted one has reported its result.
     */
    private class Operation(val key: String?) {
        @Volatile var aborted = false
    }

    /** In-flight one-shot operations per MAC. */
    private val oneShotOps = ConcurrentHashMap<String, CopyOnWriteArrayList<Operation>>()
    /** Running continuous polls per MAC. */
    private val continuousOps = ConcurrentHashMap<String, Operation>()
    /** Device ids currently owned by LiveMonitorService. */
    private val continuousDeviceIds = ConcurrentHashMap.newKeySet<Long>()
    /** deviceId -> MAC for continuous sessions. */
    private val continuousMacByDevice = ConcurrentHashMap<Long, String>()
    private val inFlightWork = AtomicInteger(0)

    fun hasInFlightWork(): Boolean = inFlightWork.get() > 0

    private suspend fun <T> withOperation(
        mac: String?,
        continuous: Boolean = false,
        block: suspend (Operation) -> T,
    ): T {
        val op = Operation(mac?.uppercase())
        val key = op.key
        if (key != null) {
            if (continuous) continuousOps[key] = op else oneShotOps.computeIfAbsent(key) { CopyOnWriteArrayList() }.add(op)
        }
        return try {
            block(op)
        } finally {
            if (key != null) {
                if (continuous) {
                    continuousOps.remove(key, op)
                } else {
                    oneShotOps[key]?.remove(op)
                }
            }
        }
    }

    private fun publishDevice(
        deviceId: Long,
        active: Boolean,
        message: String,
        latest: SpotSampleEntity? = null,
        keepLatest: Boolean = false,
        connected: Boolean? = null,
    ) {
        _state.update { current ->
            val previous = current.devices[deviceId]
            val nextDevices = current.devices + (
                deviceId to DeviceLiveState(
                    deviceId = deviceId,
                    active = active,
                    message = message,
                    latest = when {
                        latest != null -> latest
                        keepLatest -> previous?.latest
                        else -> previous?.latest
                    },
                    connected = connected ?: previous?.connected ?: false,
                )
            )
            current.copy(
                devices = nextDevices,
                activeDeviceIds = if (active) {
                    current.activeDeviceIds + deviceId
                } else {
                    current.activeDeviceIds - deviceId
                },
            )
        }
    }

    /**
     * Suspend until the live read completes. Safe to call from a polling service loop.
     * Per-MAC serialization lives in the Bluetooth gateway so different devices can overlap.
     * @param continuous true when called from LiveMonitorService so UI shows an active session.
     */
    suspend fun start(deviceId: Long, continuous: Boolean = false): Result<SpotSampleEntity> {
        inFlightWork.incrementAndGet()
        try {
        val device = repository.getDevice(deviceId)
        if (continuous) {
            continuousDeviceIds.add(deviceId)
            device?.btMac?.uppercase()?.let { continuousMacByDevice[deviceId] = it }
        }
        val continuousActive = continuousDeviceIds.contains(deviceId)
        // One-shot reads must not overwrite the dashboard live badge while the FGS polls this device.
        val publishState = continuous || !continuousActive
        if (publishState) {
            publishDevice(
                deviceId = deviceId,
                active = continuousActive || continuous,
                message = appContext.getString(R.string.live_opening_socket),
                keepLatest = true,
            )
        }
        var aborted = false
        val sessionDevice = device?.let(repository::withResolvedPin)
        val result = withOperation(device?.btMac, continuous) { op ->
            bluetoothGateway.connectAndReadLive(sessionDevice).also { aborted = op.aborted }
        }
        result.onSuccess { gatewayResult ->
            val snapshot = gatewayResult.value
            repository.saveLiveSample(deviceId, snapshot, snapshot.status ?: appContext.getString(R.string.live_read_ok))
            repository.updateDeviceStatus(
                deviceId = deviceId,
                status = snapshot.status ?: appContext.getString(R.string.live_read_ok),
                liveAtEpochSeconds = snapshot.timestampEpochSeconds,
                socketStrategy = gatewayResult.socketStrategy,
                diagnostics = gatewayResult.diagnostics,
                serial = gatewayResult.inverterSerial,
            )
            if (publishState) {
                publishDevice(
                    deviceId = deviceId,
                    active = continuousDeviceIds.contains(deviceId),
                    message = snapshot.status ?: appContext.getString(R.string.live_connected),
                    latest = snapshot,
                    connected = true,
                )
            }
            runCatching { com.alorbach.solarmonitor.widget.SolarWidgets.refreshAll(appContext) }
        }.onFailure {
            // Aborting closes the socket, so the read fails with a socket error that would
            // otherwise be shown instead of the cancellation the user asked for.
            val message = if (aborted) {
                appContext.getString(R.string.live_cancelled)
            } else {
                it.message ?: appContext.getString(R.string.live_connection_failed)
            }
            repository.updateDeviceStatus(deviceId, message)
            if (publishState) {
                publishDevice(
                    deviceId = deviceId,
                    active = continuousDeviceIds.contains(deviceId),
                    message = message,
                    keepLatest = true,
                    connected = false,
                )
            }
        }
        return result.map { it.value }.cancelledIfAborted(aborted)
        } finally {
            inFlightWork.decrementAndGet()
        }
    }

    /**
     * Closing the socket surfaces as a socket read error; callers show that message, so replace it
     * with the cancellation the user actually asked for.
     */
    private fun <T> Result<T>.cancelledIfAborted(aborted: Boolean, message: String = appContext.getString(R.string.live_cancelled)): Result<T> =
        if (aborted && isFailure) Result.failure(IllegalStateException(message, exceptionOrNull())) else this

    suspend fun testConnection(device: DeviceProfileEntity): Result<String> {
        inFlightWork.incrementAndGet()
        try {
        var aborted = false
        val sessionDevice = repository.withResolvedPin(device)
        val result = withOperation(device.btMac) { op ->
            bluetoothGateway.testConnection(sessionDevice).also { aborted = op.aborted }
        }.map { gatewayResult ->
            repository.updateDeviceStatus(
                deviceId = device.id,
                status = gatewayResult.value.message,
                socketStrategy = gatewayResult.socketStrategy,
                diagnostics = gatewayResult.diagnostics,
                serial = gatewayResult.inverterSerial,
            )
            gatewayResult.value.message
        }
        result.onFailure {
            val message = if (aborted) {
                appContext.getString(R.string.live_cancelled)
            } else {
                it.message ?: appContext.getString(R.string.live_connection_failed)
            }
            repository.updateDeviceStatus(device.id, message)
        }
        return result.cancelledIfAborted(aborted)
        } finally {
            inFlightWork.decrementAndGet()
        }
    }

    suspend fun syncHistory(
        device: DeviceProfileEntity,
        fromDate: LocalDate? = null,
        fromMonth: YearMonth? = null,
    ): Result<String> {
        inFlightWork.incrementAndGet()
        try {
        val zoneId = runCatching {
            ZoneId.of(device.timezone.takeIf { it.isNotBlank() } ?: ZoneId.systemDefault().id)
        }.getOrDefault(ZoneId.systemDefault())
        val resolvedFromDate = fromDate ?: LocalDate.now(zoneId).minusDays(30)
        val resolvedFromMonth = fromMonth ?: YearMonth.now(zoneId).minusMonths(12)
        var aborted = false
        val sessionDevice = repository.withResolvedPin(device)
        val dayResult = withOperation(device.btMac) { op ->
            bluetoothGateway.syncDayArchive(sessionDevice, resolvedFromDate).also { aborted = op.aborted }
        }
        // The gateway reports an aborted session as a failed Result rather than a cancellation, so
        // the second Bluetooth session would otherwise still be opened after Cancel.
        coroutineContext.ensureActive()
        if (aborted) {
            repository.updateDeviceStatus(device.id, appContext.getString(R.string.archive_sync_cancelled))
            return Result.failure(IllegalStateException(appContext.getString(R.string.archive_sync_cancelled)))
        }
        val monthResult = withOperation(device.btMac) { op ->
            bluetoothGateway.syncMonthArchive(sessionDevice, resolvedFromMonth).also { aborted = op.aborted }
        }
        val dayGatewayResult = dayResult.getOrNull()
        val monthGatewayResult = monthResult.getOrNull()
        // Day and month archives use separate Bluetooth sessions, so one can fail on a flaky link
        // while the other returns usable records. Keep whatever arrived instead of discarding both.
        if (dayGatewayResult == null && monthGatewayResult == null) {
            val error = dayResult.exceptionOrNull()
                ?: monthResult.exceptionOrNull()
                ?: IllegalStateException(appContext.getString(R.string.archive_sync_failed))
            val message = if (aborted) {
                appContext.getString(R.string.archive_sync_cancelled)
            } else {
                error.message ?: appContext.getString(R.string.archive_sync_failed)
            }
            repository.updateDeviceStatus(device.id, message)
            return Result.failure(if (aborted) IllegalStateException(message, error) else error)
        }

        val failure = dayResult.exceptionOrNull() ?: monthResult.exceptionOrNull()
        val failedPart = if (dayGatewayResult == null) "day" else "month"
        // Records already fetched are still saved when the user cancels, but the sync is reported as
        // cancelled rather than as a successful partial run.
        val status = when {
            aborted -> appContext.getString(R.string.archive_sync_cancelled)
            failure == null -> appContext.getString(R.string.archive_sync_ok)
            else -> appContext.getString(
                R.string.archive_sync_partial,
                failedPart,
                failure.message ?: appContext.getString(R.string.archive_sync_failed),
            )
        }
        return runCatching {
            val dayArchive = dayGatewayResult?.value
            val dayItems = dayArchive?.dayAggregates.orEmpty()
            val spotSamples = dayArchive?.spotSamples.orEmpty()
            val monthItems = monthGatewayResult?.value.orEmpty()
            repository.saveArchiveSync(
                deviceId = device.id,
                dayItems = dayItems,
                monthItems = monthItems,
                spotSamples = spotSamples,
                status = status,
            )
            repository.updateDeviceStatus(
                deviceId = device.id,
                status = status,
                archiveAtEpochSeconds = System.currentTimeMillis() / 1000,
                socketStrategy = monthGatewayResult?.socketStrategy?.ifBlank { null }
                    ?: dayGatewayResult?.socketStrategy.orEmpty(),
                diagnostics = listOfNotNull(dayGatewayResult?.diagnostics, monthGatewayResult?.diagnostics)
                    .filter { it.isNotBlank() }
                    .joinToString("\n---\n"),
                serial = monthGatewayResult?.inverterSerial ?: dayGatewayResult?.inverterSerial,
            )
            "$status: ${dayItems.size} day records, ${monthItems.size} month records, ${spotSamples.size} samples"
        }.onFailure {
            repository.updateDeviceStatus(device.id, it.message ?: appContext.getString(R.string.archive_sync_failed))
        }.let { saved ->
            if (aborted) {
                Result.failure(IllegalStateException(saved.getOrNull() ?: appContext.getString(R.string.archive_sync_cancelled)))
            } else {
                if (saved.isSuccess) {
                    cloudBackupCoordinator.enqueue(BackupTrigger.Auto)
                    runCatching { com.alorbach.solarmonitor.widget.SolarWidgets.refreshAll(appContext) }
                }
                saved
            }
        }
        } finally {
            inFlightWork.decrementAndGet()
        }
    }

    /**
     * Abort the running one-shot for [mac]. A blocked RFCOMM read ignores coroutine cancellation,
     * so the socket has to be closed for the read to return and the per-MAC mutex to be released.
     * Skipped while the foreground service owns the session for that MAC: the gateway serialises per
     * MAC, so the one-shot is then still queued on the mutex and holds no socket of its own.
     */
    fun cancelInFlight(mac: String?) {
        val key = mac?.uppercase() ?: return
        val running = oneShotOps[key]?.toList().orEmpty()
        if (running.isEmpty() || continuousOps.containsKey(key)) return
        running.forEach { it.aborted = true }
        bluetoothGateway.abortSession(key)
    }

    fun stop(deviceId: Long? = null) {
        if (deviceId != null) {
            continuousDeviceIds.remove(deviceId)
            val key = continuousMacByDevice.remove(deviceId)
            if (key != null) {
                continuousOps[key]?.aborted = true
                bluetoothGateway.abortSession(key)
            }
            publishDevice(deviceId, active = false, message = appContext.getString(R.string.live_stopped), keepLatest = true)
            return
        }
        stopAll()
    }

    fun stopAll() {
        continuousDeviceIds.clear()
        continuousMacByDevice.clear()
        val keys = (continuousOps.keys + oneShotOps.keys).toSet()
        keys.forEach { key ->
            continuousOps[key]?.aborted = true
            oneShotOps[key]?.forEach { it.aborted = true }
            bluetoothGateway.abortSession(key)
        }
        continuousOps.clear()
        _state.update { current ->
            current.copy(
                activeDeviceIds = emptySet(),
                devices = current.devices.mapValues { (_, state) ->
                    state.copy(active = false, message = appContext.getString(R.string.live_stopped))
                },
            )
        }
    }

    suspend fun stopDevice(deviceId: Long) {
        continuousDeviceIds.remove(deviceId)
        val key = continuousMacByDevice.remove(deviceId)
            ?: repository.getDevice(deviceId)?.btMac?.uppercase()
        if (key != null) {
            continuousOps[key]?.let {
                it.aborted = true
                bluetoothGateway.abortSession(key)
            }
        }
        publishDevice(deviceId, active = false, message = appContext.getString(R.string.live_stopped), keepLatest = true)
    }
}
