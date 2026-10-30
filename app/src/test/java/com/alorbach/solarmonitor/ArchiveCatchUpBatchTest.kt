package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.model.ArchiveSyncProgressEntity
import com.alorbach.solarmonitor.domain.ArchiveCatchUpBatch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveCatchUpBatchTest {
    private val now = Instant.parse("2026-10-30T11:00:00Z")
    private val zone = ZoneId.of("Europe/Berlin")
    private val today = now.atZone(zone).toLocalDate()

    private class SavedProgress(private val instant: Instant) {
        val records = mutableMapOf<LocalDate, ArchiveSyncProgressEntity>()
        fun save(day: LocalDate, outcome: String = "saved") {
            records[day] = ArchiveSyncProgressEntity(1, day.toEpochDay(), instant.epochSecond, outcome)
        }
    }

    @Test fun todayAndOnePreviousDayAreFetchedBeforeYieldingToLive() = runBlocking {
        val store = SavedProgress(now)
        val reads = mutableListOf<LocalDate>()
        ArchiveCatchUpBatch.run(zone, { now }, { true }, { store.records.values.toList() }) {
            reads += it
            store.save(it)
        }
        assertEquals(listOf(today, today.minusDays(30)), reads)
    }

    @Test fun hourlyLiveIntervalStillMakesProgressOnPreviousDays() = runBlocking {
        val store = SavedProgress(now)
        val reads = mutableListOf<LocalDate>()
        for (offset in 0L..2L) {
            val instant = now.plusSeconds(offset * 3600)
            ArchiveCatchUpBatch.run(zone, { instant }, { true }, { store.records.values.toList() }) {
                reads += it
                store.records[it] = ArchiveSyncProgressEntity(1, it.toEpochDay(), instant.epochSecond, "saved")
            }
        }
        assertEquals(listOf(today, today.minusDays(30), today, today.minusDays(29), today, today.minusDays(28)), reads)
    }

    @Test fun windowClosingAfterTodayPreventsHistoricalRequest() = runBlocking {
        val store = SavedProgress(now)
        val reads = mutableListOf<LocalDate>()
        var open = true
        ArchiveCatchUpBatch.run(zone, { now }, { open }, { store.records.values.toList() }) {
            reads += it
            store.save(it)
            open = false
        }
        assertEquals(listOf(today), reads)
    }

    @Test fun stopDuringSuspendingProgressLoadPreventsAnyRequest() = runBlocking {
        var running = true
        val reads = mutableListOf<LocalDate>()
        ArchiveCatchUpBatch.run(zone, { now }, { running }, {
            running = false
            emptyList()
        }) { reads += it }
        assertTrue(reads.isEmpty())
    }

    @Test fun restartContinuesWithNextHistoricalDayFromPersistedRecords() = runBlocking {
        val store = SavedProgress(now).also { it.save(today); it.save(today.minusDays(1)) }
        val reads = mutableListOf<LocalDate>()
        repeat(2) {
            ArchiveCatchUpBatch.run(zone, { now }, { true }, { store.records.values.map { it.copy() } }) {
                reads += it
                store.save(it)
            }
        }
        assertEquals(listOf(today.minusDays(30), today.minusDays(29)), reads)
    }

    @Test fun failureBackoffAllowsHistoryToProgressInSameBatch() = runBlocking {
        val store = SavedProgress(now)
        val reads = mutableListOf<LocalDate>()
        ArchiveCatchUpBatch.run(zone, { now }, { true }, { store.records.values.toList() }) {
            reads += it
            store.save(it, if (it == today) "failed" else "saved")
        }
        assertEquals(listOf(today, today.minusDays(30)), reads)
    }

    @Test fun devicesUseSeparateProgressAndTheirOwnCalendarDate() = runBlocking {
        val first = SavedProgress(now).also { it.save(today) }
        val second = SavedProgress(now)
        val reads = mutableListOf<Pair<Int, LocalDate>>()
        ArchiveCatchUpBatch.run(zone, { now }, { true }, { first.records.values.toList() }) {
            reads += 1 to it; first.save(it)
        }
        val otherZone = ZoneId.of("Pacific/Kiritimati")
        val otherToday = now.atZone(otherZone).toLocalDate()
        ArchiveCatchUpBatch.run(otherZone, { now }, { true }, { second.records.values.toList() }) {
            reads += 2 to it; second.save(it)
        }
        assertEquals(listOf(1 to today.minusDays(30), 2 to otherToday, 2 to otherToday.minusDays(30)), reads)
    }

    @Test fun cancellationDoesNotStartAnotherDay() = runBlocking {
        var reads = 0
        try {
            ArchiveCatchUpBatch.run(zone, { now }, { true }, { emptyList() }) {
                reads++
                throw CancellationException("Stopped")
            }
        } catch (_: CancellationException) {
            // Cancellation must leave the batch immediately.
        }
        assertEquals(1, reads)
    }

    @Test fun onePollPerDayFetchesOldUnattemptedDatesInsteadOfRepeatingYesterday() = runBlocking {
        val records = mutableMapOf<LocalDate, ArchiveSyncProgressEntity>()
        val historicalReads = mutableListOf<LocalDate>()
        for (offset in 0L..4L) {
            val instant = now.plusSeconds(offset * 86400)
            val localToday = instant.atZone(zone).toLocalDate()
            val reads = mutableListOf<LocalDate>()
            ArchiveCatchUpBatch.run(zone, { instant }, { true }, { records.values.map { it.copy() } }) {
                reads += it
                records[it] = ArchiveSyncProgressEntity(1, it.toEpochDay(), instant.epochSecond, "saved")
            }
            assertEquals(listOf(localToday, localToday.minusDays(30)), reads)
            historicalReads += reads.last()
        }
        assertEquals((0L..4L).map { today.minusDays(30).plusDays(it) }, historicalReads)
    }
}
