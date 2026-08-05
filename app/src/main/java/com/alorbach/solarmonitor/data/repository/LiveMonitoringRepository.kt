package com.alorbach.solarmonitor.data.repository

import com.alorbach.solarmonitor.data.model.SpotSampleEntity
import com.alorbach.solarmonitor.device.SmaLegacyBluetoothGateway
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LiveMonitoringState(
    val deviceId: Long? = null,
    val active: Boolean = false,
    val message: String = "Idle",
    val latest: SpotSampleEntity? = null,
)

class LiveMonitoringRepository(
    private val repository: SolarRepository,
    private val bluetoothGateway: SmaLegacyBluetoothGateway,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(LiveMonitoringState())
    val state: StateFlow<LiveMonitoringState> = _state

    fun start(deviceId: Long, onComplete: ((Result<SpotSampleEntity>) -> Unit)? = null) {
        scope.launch {
            _state.value = LiveMonitoringState(deviceId = deviceId, active = true, message = "Opening socket")
            val device = repository.getDevice(deviceId)
            val result = bluetoothGateway.connectAndReadLive(device)
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
                _state.value = LiveMonitoringState(
                    deviceId = deviceId,
                    active = true,
                    message = snapshot.status ?: "Connected",
                    latest = snapshot,
                )
                onComplete?.invoke(Result.success(snapshot))
            }.onFailure {
                repository.updateDeviceStatus(deviceId, it.message ?: "Connection failed")
                _state.value = LiveMonitoringState(
                    deviceId = deviceId,
                    active = false,
                    message = it.message ?: "Connection failed",
                )
                onComplete?.invoke(Result.failure(it))
            }
        }
    }

    fun testConnection(deviceId: Long, onComplete: (Result<String>) -> Unit) {
        scope.launch {
            val device = repository.getDevice(deviceId)
            val result = bluetoothGateway.testConnection(device).map { gatewayResult ->
                repository.updateDeviceStatus(
                    deviceId = deviceId,
                    status = gatewayResult.value.message,
                    socketStrategy = gatewayResult.socketStrategy,
                    diagnostics = gatewayResult.diagnostics,
                )
                gatewayResult.value.message
            }
            result.onFailure {
                repository.updateDeviceStatus(deviceId, it.message ?: "Connection failed")
            }
            onComplete(result)
        }
    }

    fun syncHistory(
        deviceId: Long,
        fromDate: LocalDate = LocalDate.now().minusDays(30),
        fromMonth: YearMonth = YearMonth.now().minusMonths(12),
        onComplete: (Result<String>) -> Unit,
    ) {
        scope.launch {
            val device = repository.getDevice(deviceId)
            val dayResult = bluetoothGateway.syncDayArchive(device, fromDate)
            val monthResult = bluetoothGateway.syncMonthArchive(device, fromMonth)
            val combined = runCatching {
                val dayGatewayResult = dayResult.getOrThrow()
                val monthGatewayResult = monthResult.getOrThrow()
                val dayItems = dayGatewayResult.value
                val monthItems = monthGatewayResult.value
                repository.saveArchiveSync(
                    deviceId = deviceId,
                    dayItems = dayItems,
                    monthItems = monthItems,
                    status = "Archive sync OK",
                )
                repository.updateDeviceStatus(
                    deviceId = deviceId,
                    status = "Archive sync OK",
                    archiveAtEpochSeconds = System.currentTimeMillis() / 1000,
                    socketStrategy = monthGatewayResult.socketStrategy.ifBlank { dayGatewayResult.socketStrategy },
                    diagnostics = listOf(dayGatewayResult.diagnostics, monthGatewayResult.diagnostics)
                        .filter { it.isNotBlank() }
                        .joinToString("\n---\n"),
                )
                "Archive sync OK: ${dayItems.size} day records, ${monthItems.size} month records"
            }
            combined.onFailure {
                repository.updateDeviceStatus(deviceId, it.message ?: "Archive sync failed")
            }
            onComplete(combined)
        }
    }

    fun stop() {
        _state.value = LiveMonitoringState(message = "Stopped")
    }
}
