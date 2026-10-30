package com.alorbach.solarmonitor.device

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Coroutine timeouts alone cannot interrupt BluetoothSocket.connect(). */
internal fun <T> withBlockingIoDeadline(timeoutMillis: Long, abort: () -> Unit, block: () -> T): T {
    // A successful operation and the deadline race to claim completion. Once complete,
    // a late timer must never close a socket already handed to the protocol session.
    val state = AtomicInteger(0) // 0 running, 1 completed, 2 timed out
    val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "bluetooth-connect-deadline").apply { isDaemon = true }
    }
    val deadline = executor.schedule({
        if (state.compareAndSet(0, 2)) runCatching(abort)
    }, timeoutMillis, TimeUnit.MILLISECONDS)
    return try {
        val result = block()
        if (!state.compareAndSet(0, 1)) {
            throw IOException("Bluetooth connection timed out after ${timeoutMillis}ms")
        }
        result
    } finally {
        state.compareAndSet(0, 1)
        deadline.cancel(false)
        executor.shutdownNow()
    }
}
