package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.service.AppProcessRestarter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessRestarterTest {
    @Test
    fun isRelayProcessName_matchesRestartSuffixOnly() {
        assertTrue(AppProcessRestarter.isRelayProcessName("com.alorbach.solarmonitor:restart"))
        assertFalse(AppProcessRestarter.isRelayProcessName("com.alorbach.solarmonitor"))
        assertFalse(AppProcessRestarter.isRelayProcessName("com.alorbach.solarmonitor:live"))
    }
}
