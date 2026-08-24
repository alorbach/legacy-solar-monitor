package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.domain.HomeWifiPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWifiPolicyTest {
    @Test
    fun disabledCheckAllowsAnyNetwork() {
        assertTrue(HomeWifiPolicy.isAllowed(false, null, emptySet()))
    }

    @Test
    fun emptyAllowlistAllowsAnyNetworkWhenCheckEnabled() {
        assertTrue(HomeWifiPolicy.isAllowed(true, null, emptySet()))
        assertTrue(HomeWifiPolicy.isAllowed(true, "AnyNet", emptySet()))
    }

    @Test
    fun matchingSsidIsAllowed() {
        assertTrue(HomeWifiPolicy.isAllowed(true, "\"HomeNet\"", setOf("HomeNet")))
    }

    @Test
    fun differentSsidIsRejected() {
        assertFalse(HomeWifiPolicy.isAllowed(true, "AwayNet", setOf("HomeNet")))
    }

    @Test
    fun unavailableSsidIsRejectedWhenCheckEnabled() {
        assertFalse(HomeWifiPolicy.isAllowed(true, "<unknown ssid>", setOf("HomeNet")))
        assertFalse(HomeWifiPolicy.isAllowed(true, null, setOf("HomeNet")))
    }

    @Test
    fun allowlistNormalizationRemovesDuplicatesAndPlaceholders() {
        assertEquals(
            setOf("HomeNet"),
            HomeWifiPolicy.normalizedAllowlist(
                listOf(" HomeNet ", "\"HomeNet\"", "<unknown ssid>", ""),
            ),
        )
    }
}
