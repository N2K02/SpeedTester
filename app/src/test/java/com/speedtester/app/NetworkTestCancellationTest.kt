package com.speedtester.app

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTestCancellationTest {
    @Test fun activeMeasurementIsCancelledWhenNetworkChanges() {
        val guard = NetworkTestCancellation()
        val job = Job()
        guard.attach(job)
        assertTrue(job.isActive)
        guard.networkChanged()
        assertTrue(guard.hasChanged())
        assertFalse(job.isActive)
    }

    @Test fun changeBeforeJobAttachmentStillCancelsMeasurement() {
        val guard = NetworkTestCancellation()
        guard.networkChanged()
        val job = Job()
        guard.attach(job)
        assertTrue(guard.hasChanged())
        assertFalse(job.isActive)
    }

    @Test fun stableNetworkKeepsMeasurementActive() {
        val guard = NetworkTestCancellation()
        val job = Job()
        guard.attach(job)
        assertFalse(guard.hasChanged())
        assertTrue(job.isActive)
        job.cancel()
    }

    @Test fun concurrentRegistrationAndNetworkChangeCannotLeaveActiveMeasurement() {
        repeat(100) {
            val guard = NetworkTestCancellation()
            val job = Job()
            val start = java.util.concurrent.CountDownLatch(1)
            val t1 = kotlin.concurrent.thread {
                start.await()
                guard.attach(job)
            }
            val t2 = kotlin.concurrent.thread {
                start.await()
                guard.networkChanged()
            }
            start.countDown()
            t1.join()
            t2.join()
            assertFalse(job.isActive)
        }
    }
}
