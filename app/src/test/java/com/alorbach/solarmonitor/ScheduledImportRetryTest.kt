package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.importing.ImportAlreadyRunningException
import com.alorbach.solarmonitor.work.shouldRetryScheduledImport
import java.net.SocketTimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledImportRetryTest {
    @Test
    fun retriesWhenAnotherImportIsBusy() {
        assertTrue(ImportAlreadyRunningException("busy").shouldRetryScheduledImport())
        assertTrue(IllegalStateException(ImportAlreadyRunningException("busy")).shouldRetryScheduledImport())
    }

    @Test
    fun retriesTransientNetworkFailures() {
        assertTrue(SocketTimeoutException("timeout").shouldRetryScheduledImport())
        assertTrue(RuntimeException(SocketTimeoutException("timeout")).shouldRetryScheduledImport())
    }

    @Test
    fun failsPermanentErrors() {
        assertFalse(IllegalArgumentException("bad config").shouldRetryScheduledImport())
        assertFalse(IllegalStateException("An import is already running").shouldRetryScheduledImport())
    }
}
