package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SpeedEngineRegressionTest {
    @Test fun failedUploadRequestsNeverCountUnacknowledgedBytes() {
        assertEquals(0L, TransferAccounting.countableBytes(4_194_304L, 0L, true))
        assertEquals(262_144L, TransferAccounting.countableBytes(2_097_152L, 262_144L, true))
        assertEquals(2_097_152L, TransferAccounting.countableBytes(2_097_152L, 0L, false))
    }

    @Test fun loadedPingPreservesWorstConcurrentPhaseAndMissingSignals() {
        assertEquals(210L, TransferAccounting.loadedPing(210L, 95L))
        assertEquals(95L, TransferAccounting.loadedPing(-1L, 95L))
        assertEquals(-1L, TransferAccounting.loadedPing(-1L, -1L))
        assertEquals(12.5, TransferAccounting.loadedJitter(12.5, 5.0), 0.0001)
        assertEquals(0.0, TransferAccounting.loadedJitter(Double.NaN, -4.0), 0.0001)
    }

    @Test fun exporterSharesOnlyProviderRegisteredDirectory() {
        val root = listOf(File("app/src/main"), File("src/main")).first { it.isDirectory }
        val paths = File(root, "res/xml/update_paths.xml").readText()
        val main = File(root, "java/com/speedtester/app/MainActivity.kt").readText()
        assertTrue(paths.contains("name=\"exports\" path=\"exports/\""))
        assertTrue(main.contains("File(context.cacheDir, \"exports\")"))
        assertTrue(main.contains("File(exportDir, \"speedtester-history.csv\")"))
    }

    @Test fun scanRequestMustRequestPreciseAndApproximateTogether() {
        val root = listOf(File("app/src/main"), File("src/main")).first { it.isDirectory }
        val wifi = File(root, "java/com/speedtester/app/WifiAnalyzerScreen.kt").readText()
        assertTrue(wifi.contains("RequestMultiplePermissions()"))
        assertTrue(wifi.contains("arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)"))
        assertTrue(wifi.contains("delay(WifiScanPolicy.AUTO_SCAN_INTERVAL_MS)"))
        assertEquals(60_000L, WifiScanPolicy.AUTO_SCAN_INTERVAL_MS)
    }

    @Test fun cancellationClosesActiveCallsAndSockets() {
        val root = listOf(File("app/src/main"), File("src/main")).first { it.isDirectory }
        val engine = File(root, "java/com/speedtester/app/MainActivity.kt").readText()
        assertTrue(engine.contains("finally {\n            stop.set(true)"))
        assertTrue(engine.contains("calls.toList().forEach { it.cancel() }"))
        assertTrue(engine.contains("sockets.toList().forEach { runCatching { it.close() } }"))
    }
}
