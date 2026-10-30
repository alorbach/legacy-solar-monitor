package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.service.LivePollScheduler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveResumePolicyTest {
    @Test
    fun inexactAlarmCanResumeOnOlderAndroidButRequiresUserOnModernAndroid() {
        val alarm = LivePollScheduler.FgsStartPolicy.NOTIFICATION_ONLY
        assertTrue(LivePollScheduler.canStartFromCurrentContext(alarm, false, 28))
        assertTrue(LivePollScheduler.canStartFromCurrentContext(alarm, false, 30))
        assertFalse(LivePollScheduler.canStartFromCurrentContext(alarm, false, 31))
        assertFalse(LivePollScheduler.canStartFromCurrentContext(alarm, false, 36))
        assertTrue(LivePollScheduler.canStartFromCurrentContext(alarm, true, 36))
    }

    @Test
    fun userActionAndBootExemptionAllowResume() {
        assertTrue(LivePollScheduler.canStartFromCurrentContext(
            LivePollScheduler.FgsStartPolicy.ALLOW_BACKGROUND_START, false, 36,
        ))
    }
}
