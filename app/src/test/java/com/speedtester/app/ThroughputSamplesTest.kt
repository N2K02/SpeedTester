package com.speedtester.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputSamplesTest {
    private val second = 1_000_000_000L

    @Test fun correctAverageAndWarmupBoundaryWithUnevenSamples() {
        val samples = ThroughputSamples(0L)
        samples.record(second, 1_000_000L)
        samples.record(2 * second, 3_000_000L)
        samples.record(4 * second, 7_000_000L)
        assertEquals(14.0, samples.averageMbps(), 0.001)
        // Warm-up boundary 1.5s -> 2MB; remaining 5MB / 2.5s = 16 Mbps.
        assertEquals(16.0, samples.steadyMbps(), 0.001)
    }

    @Test fun noWarmupBoundaryCrossingUsesWholePhase() {
        val samples = ThroughputSamples(0L)
        samples.record(second, 2_000_000L)
        assertEquals(16.0, samples.steadyMbps(), 0.001)
    }

    @Test fun sustainedPeakIgnoresShortSpikes() {
        val samples = ThroughputSamples(0L)
        samples.record(250_000_000L, 2_000_000L)
        samples.record(500_000_000L, 2_000_000L)
        samples.record(750_000_000L, 2_000_000L)
        samples.record(second, 2_000_000L)
        samples.record(2 * second, 3_000_000L)
        assertEquals(16.0, samples.peakMbps(), 0.001)
        assertTrue(samples.peakMbps() < 64.0)
    }

    @Test fun failedUploadIsNotCountedAsAcceptedTraffic() {
        val samples = ThroughputSamples(0L)
        samples.record(second, TransferAccounting.countableBytes(1_048_576L, 0L, true))
        samples.record(2 * second, TransferAccounting.countableBytes(2_097_152L, 262_144L, true))
        assertEquals(1.048576, samples.averageMbps(), 0.000001)
    }

    @Test fun requestSizesGrowOnlyWithConfirmedThroughput() {
        assertEquals(UploadRequestSizing.MIN_BYTES, UploadRequestSizing.choose(0L, second))
        assertEquals(UploadRequestSizing.MIN_BYTES, UploadRequestSizing.choose(1_000_000L, second))
        assertEquals(512 * 1024, UploadRequestSizing.choose(3_000_000L, second))
        assertEquals(1024 * 1024, UploadRequestSizing.choose(12_000_000L, second))
        assertEquals(UploadRequestSizing.MAX_BYTES, UploadRequestSizing.choose(40_000_000L, second))
        assertEquals(UploadRequestSizing.MIN_BYTES, UploadRequestSizing.choose(-5L, 0L))
    }

    @Test fun ignoresRegressingCountersAndDuplicateTimestamps() {
        val samples = ThroughputSamples(0L)
        samples.record(second, 1_000_000L)
        samples.record(second, 9_000_000L)
        samples.record(2 * second, 1L)
        assertEquals(4.0, samples.averageMbps(), 0.001)
    }
}
