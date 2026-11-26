package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StreamScalingTest {
    @Test fun firstIntervalWithTrafficAddsStreams() {
        assertEquals(StreamScaling.STEP, StreamScaling.streamsToAdd(4, 1_000, null, 30.0))
    }

    @Test fun keepsAddingWhileRateClimbsAtLeastTenPercent() {
        assertEquals(4, StreamScaling.streamsToAdd(8, 2_000, 35.0, 60.0))
        assertEquals(4, StreamScaling.streamsToAdd(8, 2_000, 50.0, 56.0))
    }

    @Test fun stopsWhenRateHasPlateaued() {
        assertEquals(0, StreamScaling.streamsToAdd(8, 2_000, 90.0, 94.0))
        assertEquals(0, StreamScaling.streamsToAdd(8, 2_000, 90.0, 70.0))
    }

    @Test fun neverExceedsMaximumStreams() {
        assertEquals(0, StreamScaling.streamsToAdd(StreamScaling.MAX, 2_000, 10.0, 90.0))
        assertEquals(2, StreamScaling.streamsToAdd(StreamScaling.MAX - 2, 2_000, 10.0, 90.0))
    }

    @Test fun noScalingAfterDeadlineOrWithoutTraffic() {
        assertEquals(0, StreamScaling.streamsToAdd(4, StreamScaling.SCALING_DEADLINE_MS + 1, 10.0, 90.0))
        assertEquals(0, StreamScaling.streamsToAdd(4, 1_000, null, 0.0))
        assertEquals(0, StreamScaling.streamsToAdd(4, 1_000, null, Double.NaN))
    }

    @Test fun quietIntervalDoesNotFreezeScalingEarly() {
        // Upload acknowledgements can be absent for a second: keep checking.
        assertFalse(StreamScaling.settled(4, 1_000, 0.0, 0))
        // A genuine plateau settles.
        assertTrue(StreamScaling.settled(8, 2_000, 40.0, 0))
        // Deadline or maximum always settles, and adding streams never does.
        assertTrue(StreamScaling.settled(4, StreamScaling.SCALING_DEADLINE_MS + 1, 0.0, 0))
        assertTrue(StreamScaling.settled(StreamScaling.MAX, 2_000, 0.0, 0))
        assertFalse(StreamScaling.settled(4, 1_000, 30.0, 4))
    }

    @Test fun engineScalesOnMovedBytesAndSamplesLatencyAllPhase() {
        val root = listOf(File("app/src/main"), File("src/main")).first { it.isDirectory }
        val engine = File(root, "java/com/speedtester/app/MainActivity.kt").readText()
        assertTrue(engine.contains("(movedBytes - lastCheckMoved).coerceAtLeast(0L)"))
        assertTrue(engine.contains("StreamScaling.settled(workers.size, elapsedMs, checkMbps, add)"))
        assertTrue(engine.contains("if (verifyUpload && lastIncreaseAt > warmupAt) lastIncreaseAt else end"))
        assertFalse(engine.contains("repeat(7)"))
    }

    @Test fun warmupWaitsForScalingButKeepsMeasurementWindow() {
        // Before the minimum warm-up nothing counts, even if settled.
        assertFalse(StreamScaling.warmupReached(1_500, true, 2_000, 12_000, 6_000))
        // Settled after minimum warm-up: start measuring.
        assertTrue(StreamScaling.warmupReached(2_250, true, 2_000, 12_000, 6_000))
        // Still scaling: wait...
        assertFalse(StreamScaling.warmupReached(4_000, false, 2_000, 12_000, 6_000))
        // ...but never leave less than the minimum measurement window.
        assertTrue(StreamScaling.warmupReached(6_000, false, 2_000, 12_000, 6_000))
    }

    @Test fun engineUsesAdaptiveStreamsLongerPhaseAndPooledConnections() {
        val root = listOf(File("app/src/main"), File("src/main")).first { it.isDirectory }
        val engine = File(root, "java/com/speedtester/app/MainActivity.kt").readText()
        assertTrue(engine.contains("private const val TEST_SECONDS = 12L"))
        assertTrue(engine.contains("StreamScaling.streamsToAdd(workers.size"))
        assertTrue(engine.contains("StreamScaling.warmupReached("))
        assertTrue(engine.contains("okhttp3.ConnectionPool(StreamScaling.MAX + 2"))
        assertTrue(engine.contains("protocols(listOf(okhttp3.Protocol.HTTP_1_1))"))
    }
}
