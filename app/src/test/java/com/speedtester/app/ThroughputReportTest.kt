package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ThroughputReportTest {
    private val ms = 1_000_000L

    /** Points every 250 ms at a constant payload rate in Mbps. */
    private fun steady(mbps: Double, seconds: Int): List<ThroughputReport.Point> =
        (0..seconds * 4).map { i ->
            val t = i * 250 * ms
            ThroughputReport.Point(t, (mbps * 1_000_000 / 8 * t / 1_000_000_000.0).toLong())
        }

    @Test fun measuredPayloadIsNotInflated() {
        assertEquals(87.0, ThroughputReport.payloadRate(87.0), 0.0)
        assertEquals(0.0, ThroughputReport.payloadRate(-1.0), 0.0)
        assertEquals(0.0, ThroughputReport.payloadRate(Double.NaN), 0.0)
        for (rate in listOf(10.0, 30.0, 50.0, 100.0, 300.0, 500.0, 1000.0)) {
            assertEquals(rate, ThroughputReport.measuredMbps((rate * 125_000).toLong(), 1_000_000_000L), 0.0001)
        }
        assertEquals(0.0, ThroughputReport.measuredMbps(1000, 0), 0.0)
    }

    @Test fun stabilityNeedsFourIndependentWindows() {
        assertFalse(ThroughputReport.stable(steady(100.0, 3)))
        assertTrue(ThroughputReport.stable(steady(100.0, 8)))
        assertFalse(ThroughputReport.stable(steady(0.0, 8)))
        val uneven = steady(100.0, 8).map { if (it.nanos >= 7_000_000_000L) it.copy(bytes = 87_500_000L) else it }
        assertFalse(ThroughputReport.stable(uneven))
    }

    @Test fun constantRateIsUnchangedByTrimming() {
        assertEquals(90.0, ThroughputReport.trimmedMbps(steady(90.0, 8))!!, 0.05)
    }

    @Test fun shortWifiDipDoesNotDragResultDown() {
        // 8 s at 95 Mbps with a 1 s stall in the middle.
        var bytes = 0L
        val points = (0..32).map { i ->
            val t = i * 250 * ms
            if (i > 0 && (i <= 14 || i > 18)) bytes += (95.0 * 1_000_000 / 8 / 4).toLong()
            ThroughputReport.Point(t, bytes)
        }
        val plainAverage = bytes * 8_000.0 / points.last().nanos
        val trimmed = ThroughputReport.trimmedMbps(points)!!
        assertTrue("average $plainAverage should be pulled down", plainAverage < 84.0)
        assertTrue("trimmed $trimmed should stay near 95", trimmed > 90.0 && trimmed <= 95.1)
    }

    @Test fun lumpyUploadAcknowledgementsAreSmoothedByWindows() {
        // 256 KiB confirmed every 500 ms ≈ 4.19 Mbps; 250 ms samples alternate 0 / burst.
        val points = (0..32).map { i -> ThroughputReport.Point(i * 250 * ms, (i / 2) * 262_144L) }
        val trimmed = ThroughputReport.trimmedMbps(points)!!
        assertEquals(4.19, trimmed, 0.6)
    }

    @Test fun tooFewWindowsFallsBack() {
        assertNull(ThroughputReport.trimmedMbps(steady(50.0, 1)))
        assertNull(ThroughputReport.trimmedMbps(emptyList()))
    }

    @Test fun historyKeepsDataThroughput() {
        val original = TestResult("d", 101.0, 40.0, 5, 1.0, "s", downloadData = 87.0, uploadData = 34.5)
        val decoded = HistoryJsonCodec.decode(HistoryJsonCodec.encode(listOf(original)).toString()).single()
        assertEquals(87.0, decoded.downloadData, 0.0)
        assertEquals(34.5, decoded.uploadData, 0.0)
        // Old history without the fields still loads.
        val legacy = HistoryJsonCodec.decode("""[{"date":"d","download":50,"upload":10,"ping":5,"jitter":1,"server":"s"}]""").single()
        assertEquals(0.0, legacy.downloadData, 0.0)
    }

    @Test fun csvExportsDataThroughput() {
        val csv = SpeedHistoryCsv.export(listOf(TestResult("d", 101.0, 40.0, 5, 1.0, "s", downloadData = 87.0, uploadData = 34.5)))
        val lines = csv.split("\r\n").filter { it.isNotEmpty() }
        assertTrue(lines[0].endsWith("download_data_mbps,upload_data_mbps"))
        assertTrue(lines[1].endsWith(",87.0,34.5"))
    }

    @Test fun engineReportsMeasuredPayloadAndKeepsDiagnosticTrim() {
        val root = listOf(File("app/src/main"), File("src/main")).first { it.isDirectory }
        val engine = File(root, "java/com/speedtester/app/MainActivity.kt").readText()
        assertTrue(engine.contains("ThroughputReport.trimmedMbps(windowPoints) ?: steady"))
        assertTrue(engine.contains("val headline = ThroughputReport.payloadRate(steady)"))
        assertTrue(engine.contains("samples, probeFailures.get(), probeAttempts.get(), steady)"))
        assertTrue(engine.contains("down.dataMbps, up.dataMbps)"))
    }
}
