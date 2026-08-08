package com.alorbach.solarmonitor.data.repository

import com.alorbach.solarmonitor.data.model.DeviceProfileEntity
import com.alorbach.solarmonitor.data.model.SpotSampleEntity
import com.alorbach.solarmonitor.device.SmaLegacyBluetoothGateway
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DeviceLiveState(
    val deviceId: Long,
    val active: Boolean = false,
    val message: String = "Idle",
    val latest: SpotSampleEntity? = null,
)

data class LiveMonitoringState(
    val devices: Map<Long, DeviceLiveState> = emptyMap(),
    val activeDeviceIds: Set<Long> = emptySet(),
) {
    val active: Boolean get() = activeDeviceIds.isNotEmpty()
    val deviceId: Long? get() = activeDeviceIds.firstOrNull() ?: devices.keys.firstOrNull()
    val message: String
        get() {
            if (activeDeviceIds.isEmpty()) {
                return devices.values.lastOrNull()?.message ?: "Idle"
            }
            val activeStates = activeDeviceIds.mapNotNull { devices[it] }
            val connected = activeStates.count {
                it.message.contains("OK", ignoreCase = true) ||
                    it.message.contains("Connected", ignoreCase = true) ||
                    it.latest != null
            }
            return if (activeDeviceIds.size == 1) {
                activeStates.firstOrNull()?.message ?: "Idle"
            } else {
                "${activeDeviceIds.size} inverters, $connected connected"
            }
        }
    val latest: SpotSampleEntity? get() = devices[deviceId]?.latest
}

class LiveMonitoringRepository(
    private val repository: SolarRepository,
    private val bluetoothGateway: SmaLegacyBluetoothGateway,
) {
    private val _state = MutableStateFlow(LiveMonitoringState())
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
                message = "Opening socket",
                keepLatest = true,
            )
        }
        var aborted = false
        val result = withOperation(device?.btMac, continuous) { op ->
            bluetoothGateway.connectAndReadLive(device).also { aborted = op.aborted }
        }
        result.onSuccess { gatewayResult ->
            val snapshot = gatewayResult.value
            repository.saveLiveSample(deviceId, snapshot, snapshot.status ?: "Live read OK")
            repository.updateDeviceStatus(
                deviceId = deviceId,
                status = snapshot.status ?: "Live read OK",
                liveAtEpochSeconds = snapshot.timestampEpochSeconds,
                socketStrategy = gatewayResult.socketStrategy,
                diagnostics = gatewayResult.diagnostics,
            )
            if (publishState) {
                publishDevice(
                    deviceId = deviceId,
                    active = continuousDeviceIds.contains(deviceId),
                    message = snapshot.status ?: "Connected",
                    latest = snapshot,
                )
            }
        }.onFailure {
            // Aborting closes the socket, so the read fails with a socket error that would
            // otherwise be shown instead of the cancellation the user asked for.
            val message = if (aborted) "Cancelled" else it.message ?: "Connection failed"
            repository.updateDeviceStatus(deviceId, message)
            if (publishState) {
                publishDevice(
                    deviceId = deviceId,
                    active = continuousDeviceIds.contains(deviceId),
                    message = message,
                    keepLatest = true,
                )
            }
        }
        return result.map { it.value }.cancelledIfAborted(aborted)
    }

    /**
     * Closing the socket surfaces as a socket read error; callers show that message, so replace it
     * with the cancellation the user actually asked for.
     */
    private fun <T> Result<T>.cancelledIfAborted(aborted: Boolean, message: String = "Cancelled"): Result<T> =
        if (aborted && isFailure) Result.failure(IllegalStateException(message, exceptionOrNull())) else this

    suspend fun testConnection(device: DeviceProfileEntity): Result<String> {
        var aborted = false
        val result = withOperation(device.btMac) { op ->
            bluetoothGateway.testConnection(device).also { aborted = op.aborted }
        }.map { gatewayResult ->
            repository.updateDeviceStatus(
                deviceId = device.id,
                status = gatewayResult.value.message,
                socketStrategy = gatewayResult.socketStrategy,
                diagnostics = gatewayResult.diagnostics,
            )
            gatewayResult.value.message
        }
        result.onFailure {
            val message = if (aborted) "Cancelled" else it.message ?: "Connection failed"
            repository.updateDeviceStatus(device.id, message)
        }
        return result.cancelledIfAborted(aborted)
    }

    suspend fun syncHistory(
        device: DeviceProfileEntity,
        fromDate: LocalDate = LocalDate.now().minusDays(30),
        fromMonth: YearMonth = YearMonth.now().minusMonths(12),
    ): Result<String> {
        var aborted = false
        val dayResult = withOperation(device.btMac) { op ->
            bluetoothGateway.syncDayArchive(device, fromDate).also { aborted = op.aborted }
        }
        // The gateway reports an aborted session as a failed Result rather than a cancellation, so
        // the second Bluetooth session would otherwise still be opened after Cancel.
        coroutineContext.ensureActive()
        if (aborted) {
            repository.updateDeviceStatus(device.id, "Archive sync cancelled")
            return Result.failure(IllegalStateException("Archive sync cancelled"))
        }
        val monthResult = withOperation(device.btMac) { op ->
            bluetoothGateway.syncMonthArchive(device, fromMonth).also { aborted = op.aborted }
        }
        val dayGatewayResult = dayResult.getOrNull()
        val monthGatewayResult = monthResult.getOrNull()
        // Day and month archives use separate Bluetooth sessions, so one can fail on a flaky link
        // while the other returns usable records. Keep whatever arrived instead of discarding both.
        if (dayGatewayResult == null && monthGatewayResult == null) {
            val error = dayResult.exceptionOrNull()
                ?: monthResult.exceptionOrNull()
                ?: IllegalStateException("Archive sync failed")
            val message = if (aborted) "Archive sync cancelled" else error.message ?: "Archive sync failed"
            repository.updateDeviceStatus(device.id, message)
            return Result.failure(if (aborted) IllegalStateException(message, error) else error)
        }

        val failure = dayResult.exceptionOrNull() ?: monthResult.exceptionOrNull()
        val failedPart = if (dayGatewayResult == null) "day" else "month"
        // Records already fetched are still saved when the user cancels, but the sync is reported as
        // cancelled rather than as a successful partial run.
        val status = when {
            aborted -> "Archive sync cancelled"
            failure == null -> "Archive sync OK"
            else -> "Archive sync partial ($failedPart archive: ${failure.message ?: "failed"})"
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
            )
            "$status: ${dayItems.size} day records, ${monthItems.size} month records, ${spotSamples.size} samples"
        }.onFailure {
            repository.updateDeviceStatus(device.id, it.message ?: "Archive sync failed")
        }.let { saved ->
            if (aborted) {
                Result.failure(IllegalStateException(saved.getOrNull() ?: "Archive sync cancelled"))
            } else {
                saved
            }
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
            publishDevice(deviceId, active = false, message = "Stopped", keepLatest = true)
            return
        }
        stopAll()
    }

    fun stopAll() {
        continuousDeviceIds.clear()
        continuousMacByDevice.clear()
        val keys = continuousOps.keys.toList()
        keys.forEach { key ->
            continuousOps[key]?.aborted = true
            bluetoothGateway.abortSession(key)
        }
        continuousOps.clear()
        _state.update { current ->
            current.copy(
                activeDeviceIds = emptySet(),
                devices = current.devices.mapValues { (_, state) ->
                    state.copy(active = false, message = "Stopped")
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
        publishDevice(deviceId, active = false, message = "Stopped", keepLatest = true)
    }
}
