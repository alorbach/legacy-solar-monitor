package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.device.withBlockingIoDeadline
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockingIoDeadlineTest {
    @Test
    fun deadlineAbortsBlockingOperationEvenWithoutCoroutineSuspension() {
        val aborted = CountDownLatch(1)
        val error = runCatching {
            withBlockingIoDeadline(20L, abort = { aborted.countDown() }) {
                check(aborted.await(2, TimeUnit.SECONDS)) { "Operation was not aborted" }
            }
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(0L, aborted.count)
    }

    @Test
    fun successfulOperationCancelsDeadline() {
        val aborted = CountDownLatch(1)
        assertEquals(42, withBlockingIoDeadline(100L, abort = { aborted.countDown() }) { 42 })
        assertFalse(aborted.await(200, TimeUnit.MILLISECONDS))
    }
}
