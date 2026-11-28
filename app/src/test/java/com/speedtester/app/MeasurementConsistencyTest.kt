package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test

class MeasurementConsistencyTest {
    @Test fun recoveryCannotExceedTargetOrLifetimeBudget() {
        assertEquals(4, MeasurementReliability.replacementCount(8, 12, 12, 20))
        assertEquals(2, MeasurementReliability.replacementCount(4, 12, 18, 20))
        assertEquals(0, MeasurementReliability.replacementCount(4, 12, 20, 20))
        assertEquals(0, MeasurementReliability.replacementCount(12, 12, 12, 20))
    }

    @Test fun discoveryNeedsRepeatedProbesAndRejectsOutlier() {
        assertNull(MeasurementReliability.discoveryPingMs(listOf(1L)))
        assertNull(MeasurementReliability.discoveryPingMs(listOf(-1L, 1L)))
        assertEquals(12L, MeasurementReliability.discoveryPingMs(listOf(500L, 12L, 10L)))
        assertEquals(11L, MeasurementReliability.discoveryPingMs(listOf(10L, 12L)))
    }

    @Test fun batchedUploadConfirmationsUseSustainedPeak() {
        val points = (0..16).map { i -> ThroughputReport.Point(i * 250_000_000L, (i / 4) * 3_750_000L) }
        assertEquals(30.0, ThroughputReport.sustainedPeak(points, 0.0), 0.01)
        assertEquals(12.0, ThroughputReport.sustainedPeak(emptyList(), 12.0), 0.0)
        val gauge = GaugeTransferRate(0L, 1_500_000_000L)
        gauge.sample(500_000_000L, 0L)
        gauge.sample(1_000_000_000L, 3_750_000L)
        assertEquals(20.0, gauge.sample(1_500_000_000L, 3_750_000L).toDouble(), 0.01)
    }
}
