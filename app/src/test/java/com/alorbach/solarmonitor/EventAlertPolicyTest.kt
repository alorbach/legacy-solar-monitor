package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import com.alorbach.solarmonitor.domain.EventAlertPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventAlertPolicyTest {
    @Test
    fun firstBatchBaselinesWatermarkWithoutNotify() {
        val events = listOf(warning(deviceId = 1, entryId = 10, ageSeconds = 60))
        val decision = EventAlertPolicy.evaluate(
            incoming = events,
            watermarks = emptyMap(),
            nowEpochSeconds = 1_700_000_000,
            enabled = true,
        )
        assertTrue(decision.notify.isEmpty())
        assertEquals(10L, decision.watermarks[1])
    }

    @Test
    fun combinedFirstImportBaselinesWithoutNotify() {
        val firstFile = listOf(warning(deviceId = 1, entryId = 10, ageSeconds = 3_600))
        val secondFile = listOf(warning(deviceId = 1, entryId = 11, ageSeconds = 60))
        val sequentialFirst = EventAlertPolicy.evaluate(
            incoming = firstFile,
            watermarks = emptyMap(),
            nowEpochSeconds = 1_700_000_000,
            enabled = true,
        )
        val sequentialSecond = EventAlertPolicy.evaluate(
            incoming = secondFile,
            watermarks = sequentialFirst.watermarks,
            nowEpochSeconds = 1_700_000_000,
            enabled = true,
        )
        assertEquals(listOf(11L), sequentialSecond.notify.map { it.entryId })

        val combined = EventAlertPolicy.evaluate(
            incoming = firstFile + secondFile,
            watermarks = emptyMap(),
            nowEpochSeconds = 1_700_000_000,
            enabled = true,
        )
        assertTrue(combined.notify.isEmpty())
        assertEquals(11L, combined.watermarks[1])
    }

    @Test
    fun notifiesNewWarningWithin24hAboveWatermark() {
        val events = listOf(
            warning(deviceId = 1, entryId = 11, ageSeconds = 60),
            warning(deviceId = 1, entryId = 12, ageSeconds = 50),
        )
        val decision = EventAlertPolicy.evaluate(
            incoming = events,
            watermarks = mapOf(1L to 10L),
            nowEpochSeconds = 1_700_000_000,
            enabled = true,
        )
        assertEquals(listOf(11L, 12L), decision.notify.map { it.entryId })
        assertEquals(12L, decision.watermarks[1])
    }

    @Test
    fun ignoresOldWarningsAndInfoAndDisabled() {
        val oldWarning = warning(deviceId = 1, entryId = 20, ageSeconds = EventAlertPolicy.WINDOW_SECONDS + 10)
        val info = event(deviceId = 1, entryId = 21, category = "Event", ageSeconds = 10)
        val now = 1_700_000_000L
        val old = EventAlertPolicy.evaluate(listOf(oldWarning), mapOf(1L to 10L), now, enabled = true)
        assertTrue(old.notify.isEmpty())
        val infos = EventAlertPolicy.evaluate(listOf(info), mapOf(1L to 10L), now, enabled = true)
        assertTrue(infos.notify.isEmpty())
        val disabled = EventAlertPolicy.evaluate(
            listOf(warning(deviceId = 1, entryId = 22, ageSeconds = 5)),
            mapOf(1L to 10L),
            now,
            enabled = false,
        )
        assertTrue(disabled.notify.isEmpty())
        assertEquals(22L, disabled.watermarks[1])
    }

    @Test
    fun watermarkRoundTrip() {
        val encoded = EventAlertPolicy.encodeWatermarks(mapOf(2L to 9L, 1L to 4L))
        assertEquals("1:4,2:9", encoded)
        assertEquals(mapOf(1L to 4L, 2L to 9L), EventAlertPolicy.parseWatermarks(encoded))
        val remaining = EventAlertPolicy.parseWatermarks(encoded) - 1L
        assertEquals("2:9", EventAlertPolicy.encodeWatermarks(remaining))
    }

    private fun warning(deviceId: Long, entryId: Long, ageSeconds: Long) =
        event(deviceId, entryId, category = "Warning", ageSeconds = ageSeconds)

    private fun event(
        deviceId: Long,
        entryId: Long,
        category: String,
        ageSeconds: Long,
    ) = DeviceEventEntity(
        id = entryId,
        deviceId = deviceId,
        entryId = entryId,
        timestampEpochSeconds = 1_700_000_000 - ageSeconds,
        eventCode = if (category.equals("Warning", ignoreCase = true)) 33 else 10223,
        eventType = if (category.equals("Warning", ignoreCase = true)) "Incoming" else "Outgoing",
        category = category,
        eventGroup = "Grid",
        tag = "warn",
        oldValue = "0",
        newValue = "1",
        userGroup = null,
    )
}
