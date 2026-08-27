package com.alorbach.solarmonitor.data.local

import com.alorbach.solarmonitor.data.model.SpotSampleEntity

object SpotSampleMerger {
    fun merge(existing: SpotSampleEntity, incoming: SpotSampleEntity): SpotSampleEntity {
        val existingIsLive = existing.sourceType == "bluetooth_live"
        val incomingIsLive = incoming.sourceType == "bluetooth_live"
        val preferred = when {
            existingIsLive && !incomingIsLive -> existing
            else -> incoming
        }
        val fallback = if (preferred === incoming) existing else incoming
        val hasArchive = existing.sourceType == "bluetooth_day_archive" ||
            incoming.sourceType == "bluetooth_day_archive"
        return preferred.copy(
            id = existing.id,
            pdc1 = preferred.pdc1 ?: fallback.pdc1,
            pdc2 = preferred.pdc2 ?: fallback.pdc2,
            pac1 = preferred.pac1 ?: fallback.pac1,
            pac2 = preferred.pac2 ?: fallback.pac2,
            pac3 = preferred.pac3 ?: fallback.pac3,
            totalPac = preferred.totalPac ?: fallback.totalPac,
            eTodayWh = preferred.eTodayWh ?: fallback.eTodayWh,
            eTotalWh = preferred.eTotalWh ?: fallback.eTotalWh,
            frequencyHz = preferred.frequencyHz ?: fallback.frequencyHz,
            temperatureC = preferred.temperatureC ?: fallback.temperatureC,
            status = preferred.status ?: fallback.status,
            gridRelay = preferred.gridRelay ?: fallback.gridRelay,
            btSignalPercent = preferred.btSignalPercent ?: fallback.btSignalPercent,
            sourceType = if (hasArchive) "bluetooth_day_archive" else preferred.sourceType,
        )
    }
}
