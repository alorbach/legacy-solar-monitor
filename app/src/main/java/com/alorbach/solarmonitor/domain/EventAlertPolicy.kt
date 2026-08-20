package com.alorbach.solarmonitor.domain

import com.alorbach.solarmonitor.data.model.DeviceEventEntity

object EventAlertPolicy {
    const val WINDOW_SECONDS = 24L * 3600L

    fun parseWatermarks(raw: String): Map<Long, Long> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(',')
            .mapNotNull { token ->
                val parts = token.split(':')
                if (parts.size != 2) return@mapNotNull null
                val deviceId = parts[0].toLongOrNull() ?: return@mapNotNull null
                val entryId = parts[1].toLongOrNull() ?: return@mapNotNull null
                deviceId to entryId
            }
            .toMap()
    }

    fun encodeWatermarks(map: Map<Long, Long>): String =
        map.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

    data class Decision(
        val notify: List<DeviceEventEntity>,
        val watermarks: Map<Long, Long>,
    )

    fun evaluate(
        incoming: List<DeviceEventEntity>,
        watermarks: Map<Long, Long>,
        nowEpochSeconds: Long,
        enabled: Boolean,
    ): Decision {
        if (incoming.isEmpty()) return Decision(emptyList(), watermarks)
        val next = watermarks.toMutableMap()
        val notify = mutableListOf<DeviceEventEntity>()
        val windowStart = nowEpochSeconds - WINDOW_SECONDS
        incoming.groupBy { it.deviceId }.forEach { (deviceId, events) ->
            val maxEntry = events.maxOf { it.entryId }
            val previous = next[deviceId]
            if (previous == null) {
                next[deviceId] = maxEntry
                return@forEach
            }
            if (enabled) {
                notify += events.filter { event ->
                    event.entryId > previous &&
                        event.timestampEpochSeconds >= windowStart &&
                        EventCatalog.severity(event) == EventSeverity.WARNING
                }
            }
            next[deviceId] = maxOf(previous, maxEntry)
        }
        return Decision(notify = notify.sortedBy { it.timestampEpochSeconds }, watermarks = next)
    }
}
