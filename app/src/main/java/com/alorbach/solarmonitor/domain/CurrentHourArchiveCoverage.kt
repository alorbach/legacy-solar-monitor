package com.alorbach.solarmonitor.domain

import java.time.Instant
import java.time.ZoneId

object CurrentHourArchiveCoverage {
    fun isCovered(
        latestArchiveEpochSeconds: Long?,
        nowEpochSeconds: Long,
        zoneId: ZoneId,
    ): Boolean {
        val latest = latestArchiveEpochSeconds ?: return false
        if (latest > nowEpochSeconds) return false
        val currentHourStart = Instant.ofEpochSecond(nowEpochSeconds)
            .atZone(zoneId)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
            .toEpochSecond()
        return latest >= currentHourStart
    }
}
