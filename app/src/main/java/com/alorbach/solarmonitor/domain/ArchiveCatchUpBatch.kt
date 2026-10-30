package com.alorbach.solarmonitor.domain

import com.alorbach.solarmonitor.data.model.ArchiveSyncProgressEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Each live poll can advance today and one older day, never an unbounded history session. */
object ArchiveCatchUpBatch {
    suspend fun run(
        zone: ZoneId,
        now: () -> Instant = Instant::now,
        allowed: suspend () -> Boolean,
        progress: suspend () -> List<ArchiveSyncProgressEntity>,
        readAndSave: suspend (LocalDate) -> Unit,
    ) {
        repeat(2) {
            coroutineContext.ensureActive()
            if (!allowed()) return
            val instant = now()
            val date = ArchiveCatchUpPolicy.nextDate(instant, zone, progress()) ?: return
            coroutineContext.ensureActive()
            // Progress loading may suspend across a window boundary or a user Stop.
            if (!allowed()) return
            readAndSave(date)
            if (date != instant.atZone(zone).toLocalDate()) return
        }
    }
}
