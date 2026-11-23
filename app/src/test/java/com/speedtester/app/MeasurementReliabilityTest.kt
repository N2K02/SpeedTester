package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test

class MeasurementReliabilityTest {
    @Test fun allExhaustedStreamsInvalidatePartialResult() {
        assertTrue(MeasurementReliability.allWorkersFailed(4, 4, false))
        assertFalse(MeasurementReliability.allWorkersFailed(3, 4, false))
        assertFalse(MeasurementReliability.allWorkersFailed(4, 4, true))
        assertFalse(MeasurementReliability.allWorkersFailed(0, 0, false))
    }

    @Test fun blockedIdleTcpProbesDoNotInventLatency() {
        assertEquals(-1L, MeasurementReliability.idlePingMs(emptyList()))
        assertEquals(0.0, MeasurementReliability.idleJitterMs(emptyList()), 0.0001)
        assertEquals(40L, MeasurementReliability.idlePingMs(listOf(-1, 40L)))
        assertEquals(0.0, MeasurementReliability.idleJitterMs(listOf(-1, 40L)), 0.0001)
    }

    @Test fun successfulTcpLatencyUsesOnlyValidReadings() {
        assertEquals(35L, MeasurementReliability.idlePingMs(listOf(30L, -1L, 40L)))
        assertEquals(10.0, MeasurementReliability.idleJitterMs(listOf(30L, -1L, 40L)), 0.0001)
    }

    @Test fun legacyDownloadAndUploadAccountingRemainsUnchanged() {
        assertEquals(10_000_000L, TransferAccounting.countableBytes(10_000_000L, 0L, false))
        assertEquals(0L, TransferAccounting.countableBytes(10_000_000L, 0L, true))
        assertEquals(262_144L, TransferAccounting.countableBytes(10_000_000L, 262_144L, true))
    }
}
