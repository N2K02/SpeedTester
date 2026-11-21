package com.speedtester.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GaugeTransferRateTest {
    @Test fun uploadNeedleRespondsBeforeServerAcknowledgment() {
        val gauge = GaugeTransferRate(0L)
        val sent = 64L * 1024L

        assertEquals(2.097152f, gauge.sample(250_000_000L, sent), 0.0001f)
        assertEquals(0L, TransferAccounting.countableBytes(sent, acknowledged = 0L, upload = true))
        assertEquals(2.097152f, gauge.sample(500_000_000L, 2L * sent), 0.0001f)
    }

    @Test fun briefTrafficGapDoesNotZeroTheNeedleAndIdleTrafficAgesOut() {
        val gauge = GaugeTransferRate(0L)

        assertEquals(8f, gauge.sample(250_000_000L, 250_000L), 0.0001f)
        assertEquals(4f, gauge.sample(500_000_000L, 250_000L), 0.0001f)
        assertEquals(8f, gauge.sample(750_000_000L, 750_000L), 0.0001f)
        assertEquals(16f / 3f, gauge.sample(1_000_000_000L, 750_000L), 0.0001f)
        assertEquals(16f / 3f, gauge.sample(1_250_000_000L, 750_000L), 0.0001f)
        assertEquals(0f, gauge.sample(1_500_000_000L, 750_000L), 0f)
    }

    @Test fun irregularSamplingUsesElapsedTime() {
        val gauge = GaugeTransferRate(50_000_000L)

        assertEquals(8f, gauge.sample(250_000_000L, 200_000L), 0.0001f)
        assertEquals(8f, gauge.sample(670_000_000L, 620_000L), 0.0001f)
        assertEquals(8f, gauge.sample(1_100_000_000L, 1_050_000L), 0.0001f)
    }

    @Test fun duplicateTimestampsAndRegressingCountersDoNotCreateSpikes() {
        val gauge = GaugeTransferRate(0L)

        assertEquals(0f, gauge.sample(0L, 100_000L), 0f)
        assertEquals(8f, gauge.sample(250_000_000L, 250_000L), 0.0001f)
        assertEquals(8f, gauge.sample(250_000_000L, 1_000_000L), 0.0001f)
        assertEquals(4f, gauge.sample(500_000_000L, 150_000L), 0.0001f)
    }
}
