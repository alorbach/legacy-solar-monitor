package com.alorbach.solarmonitor.domain

import com.alorbach.solarmonitor.data.model.ArchiveSyncProgressEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Scheduling uses completed requests, never the presence of a sample as proof of coverage. */
object ArchiveCatchUpPolicy {
    const val RETRY_SECONDS = 15 * 60L

    fun nextDate(now: Instant, zone: ZoneId, progress: List<ArchiveSyncProgressEntity>): LocalDate? {
        val today = now.atZone(zone).toLocalDate()
        val byDay = progress.associateBy { it.dateEpochDay }
        val history = (1L..30L).map { today.minusDays(it) }
        // Preserve a chance to fetch old days before they leave the rolling window, even when
        // only one successful live poll occurs each day. Rechecks follow only after first attempts.
        val unattempted = history.asReversed().filter { it.toEpochDay() !in byDay }
        val rechecks = history.filter { it.toEpochDay() in byDay }
        return (listOf(today) + unattempted + rechecks).firstOrNull { date ->
            val last = byDay[date.toEpochDay()] ?: return@firstOrNull true
            val attempted = Instant.ofEpochSecond(last.attemptedAtEpochSeconds)
            when (last.outcome) {
                "failed" -> now.epochSecond - attempted.epochSecond >= RETRY_SECONDS
                "empty" -> if (date == today) {
                    now.epochSecond - attempted.epochSecond >= 3600
                } else {
                    attempted.atZone(zone).toLocalDate() != today
                }
                else -> if (date == today) {
                    now.epochSecond - attempted.epochSecond >= 3600
                } else {
                    attempted.atZone(zone).toLocalDate() != today
                }
            }
        }
    }
}
