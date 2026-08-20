package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import com.alorbach.solarmonitor.domain.EventListGrouping
import com.alorbach.solarmonitor.domain.EventListRow
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventListGroupingTest {
    private val zone = ZoneOffset.UTC

    @Test
    fun consecutiveSameCodeSameDayCollapses() {
        val day = 1_700_000_000L
        val events = listOf(
            event(id = 3, code = 10223, ts = day),
            event(id = 2, code = 10223, ts = day - 60),
            event(id = 1, code = 10223, ts = day - 120),
        )
        val rows = EventListGrouping.rows(events, zone)
        assertEquals(2, rows.size)
        assertTrue(rows[0] is EventListRow.DateHeader)
        val cluster = rows[1] as EventListRow.Cluster
        assertEquals(3, cluster.item.count)
        assertEquals(10223, cluster.item.representative.eventCode)
    }

    @Test
    fun sameCodeAcrossMidnightDoesNotCollapse() {
        val late = 1_704_067_199L
        val dayStart = 1_704_153_600L
        val events = listOf(
            event(id = 2, code = 10223, ts = dayStart + 60),
            event(id = 1, code = 10223, ts = late),
        )
        val rows = EventListGrouping.rows(events, zone)
        val clusters = rows.filterIsInstance<EventListRow.Cluster>()
        assertEquals(2, clusters.size)
        assertEquals(1, clusters[0].item.count)
        assertEquals(1, clusters[1].item.count)
        assertEquals(2, rows.filterIsInstance<EventListRow.DateHeader>().size)
    }

    @Test
    fun differentCodesStaySeparate() {
        val ts = 1_700_000_000L
        val rows = EventListGrouping.rows(
            listOf(
                event(id = 2, code = 33, ts = ts),
                event(id = 1, code = 10223, ts = ts - 10),
            ),
            zone,
        )
        assertEquals(2, rows.filterIsInstance<EventListRow.Cluster>().size)
    }

    @Test
    fun usefulOldNewSkipsZerosAndEquals() {
        assertNull(EventListGrouping.usefulOldNew(event(oldValue = "0", newValue = "0")))
        assertNull(EventListGrouping.usefulOldNew(event(oldValue = "1", newValue = "1")))
        assertNull(EventListGrouping.usefulOldNew(event(oldValue = null, newValue = null)))
        assertEquals("0" to "3601", EventListGrouping.usefulOldNew(event(oldValue = "0", newValue = "3601")))
    }

    private fun event(
        id: Long = 1,
        code: Int = 10223,
        ts: Long = 1_700_000_000L,
        oldValue: String? = "0",
        newValue: String? = "1",
    ) = DeviceEventEntity(
        id = id,
        deviceId = 1,
        entryId = id,
        timestampEpochSeconds = ts,
        eventCode = code,
        eventType = "Outgoing",
        category = "Event",
        eventGroup = "Grid",
        tag = "raw",
        oldValue = oldValue,
        newValue = newValue,
        userGroup = null,
    )
}
