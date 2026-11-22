package com.speedtester.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseTransfersTest {
    @Test fun stoppingCancelsActiveTransfersAndRejectsLateOnes() {
        val phase = PhaseTransfers()
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val cancellations = AtomicInteger()
        val worker = thread(isDaemon = true) {
            phase.run(cancel = { cancellations.incrementAndGet(); cancelled.countDown() }) {
                started.countDown()
                cancelled.await(5, TimeUnit.SECONDS)
            }
        }

        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            phase.stop()
            phase.stop()
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertTrue(phase.stopped)
            assertEquals(1, cancellations.get())

            var executed = false
            phase.run(cancel = { cancellations.incrementAndGet() }) { executed = true }
            assertFalse(executed)
            assertEquals(2, cancellations.get())
        } finally {
            phase.stop()
        }
    }

    @Test fun completedRequestsAreRemovedBeforeShutdown() {
        val phase = PhaseTransfers()
        var cancelled = false
        phase.run(cancel = { cancelled = true }) {}
        phase.stop()
        assertFalse(cancelled)
    }
}
