package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.device.SmaStatusLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmaStatusLabelsTest {
    @Test
    fun healthTagsMapToLocalizedResources() {
        assertEquals(R.string.live_health_ok, SmaStatusLabels.healthLabelRes(0x133))
        assertEquals(R.string.live_health_warning, SmaStatusLabels.healthLabelRes(0x1c7))
        assertEquals(R.string.live_health_fault, SmaStatusLabels.healthLabelRes(0x23))
        assertEquals(R.string.live_health_off, SmaStatusLabels.healthLabelRes(0x12f))
        assertEquals(R.string.live_health_waiting_dc, SmaStatusLabels.healthLabelRes(0x1c8))
        assertEquals(R.string.live_health_waiting_grid, SmaStatusLabels.healthLabelRes(0x1c9))
    }

    @Test
    fun relayTagsMapToLocalizedResources() {
        assertEquals(R.string.live_relay_closed, SmaStatusLabels.relayLabelRes(0x33))
        assertEquals(R.string.live_relay_open, SmaStatusLabels.relayLabelRes(0x137))
    }

    @Test
    fun encodesStableTokensNotLocalizedText() {
        assertEquals("sma_health:307", SmaStatusLabels.encodeHealth(0x133))
        assertEquals("sma_relay:51", SmaStatusLabels.encodeRelay(0x33))
    }

    @Test
    fun parsesStableAndLegacyTokens() {
        assertEquals(307, SmaStatusLabels.parseHealthId("sma_health:307"))
        assertEquals(307, SmaStatusLabels.parseHealthId("Health 0x133"))
        assertEquals(51, SmaStatusLabels.parseRelayId("sma_relay:51"))
        assertEquals(51, SmaStatusLabels.parseRelayId("Relay 0x33"))
        assertNull(SmaStatusLabels.parseHealthId("Live read OK"))
        assertNull(SmaStatusLabels.parseRelayId("Closed"))
    }

    @Test
    fun unknownTagsKeepHexFallback() {
        assertNull(SmaStatusLabels.healthLabelRes(0x999))
        assertNull(SmaStatusLabels.relayLabelRes(0x999))
        assertEquals("0x999", SmaStatusLabels.hex(0x999))
    }
}
