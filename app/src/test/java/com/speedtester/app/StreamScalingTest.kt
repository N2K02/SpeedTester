package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StreamScalingTest {
    @Test fun exploresEvenAfterOneFlatInterval() {
        assertEquals(4, StreamScaling.streamsToAdd(4, 1000, null, 30.0))
        assertEquals(4, StreamScaling.streamsToAdd(8, 2000, 40.0, 40.0))
        assertEquals(0, StreamScaling.streamsToAdd(12, 3000, 90.0, 94.0))
        assertEquals(4, StreamScaling.streamsToAdd(12, 3000, 50.0, 56.0))
        assertEquals(0, StreamScaling.streamsToAdd(8, 2000, 40.0, 40.0, true))
    }

    @Test fun respectsBoundsAndDeadline() {
        assertEquals(0, StreamScaling.streamsToAdd(16, 2000, 10.0, 90.0))
        assertEquals(2, StreamScaling.streamsToAdd(14, 2000, 10.0, 90.0))
        assertEquals(0, StreamScaling.streamsToAdd(4, StreamScaling.SCALING_DEADLINE_MS, 10.0, 90.0))
        assertEquals(0, StreamScaling.streamsToAdd(4, 1000, null, 0.0))
        assertEquals(0, StreamScaling.streamsToAdd(4, 1000, null, Double.NaN))
    }

    @Test fun plateauMustPersistAndMaxStreamsNeedWarmup() {
        assertFalse(StreamScaling.settled(12, 3000, 90.0, 0, 1))
        assertTrue(StreamScaling.settled(12, 4000, 90.0, 0, 2))
        assertFalse(StreamScaling.settled(16, 3000, 90.0, 4, 0))
        assertFalse(StreamScaling.settled(16, 4000, 90.0, 0, 1))
        assertTrue(StreamScaling.settled(16, 5000, 90.0, 0, 2))
        assertFalse(StreamScaling.settled(4, 1000, 0.0, 0, 0))
        assertTrue(StreamScaling.settled(4, StreamScaling.SCALING_DEADLINE_MS, 0.0, 0, 0))
    }

    @Test fun warmupPreservesMeasurementWindow() {
        assertFalse(StreamScaling.warmupReached(1500, true, 2000, 12000, 6000))
        assertTrue(StreamScaling.warmupReached(2250, true, 2000, 12000, 6000))
        assertFalse(StreamScaling.warmupReached(4000, false, 2000, 12000, 6000))
        assertTrue(StreamScaling.warmupReached(6000, false, 2000, 12000, 6000))
    }

    @Test fun engineUsesAdaptiveStreamsAndFreezesSampleBeforeCleanup() {
        val root = listOf(File("app/src/main"), File("src/main")).first { it.isDirectory }
        val engine = File(root, "java/com/speedtester/app/MainActivity.kt").readText()
        assertTrue(engine.contains("StreamScaling.streamsToAdd(targetStreams"))
        assertTrue(engine.contains("StreamScaling.warmupReached("))
        assertTrue(engine.contains("val end = lastAt"))
        assertTrue(engine.contains("val total = lastBytes"))
        assertTrue(engine.contains("ThroughputReport.stable(windowPoints)"))
        assertTrue(engine.contains("okhttp3.ConnectionPool(StreamScaling.MAX + 2"))
        assertTrue(engine.contains("protocols(listOf(okhttp3.Protocol.HTTP_1_1))"))
    }
}
