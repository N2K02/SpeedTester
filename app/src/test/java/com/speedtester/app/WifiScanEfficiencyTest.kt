package com.speedtester.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WifiScanEfficiencyTest {
    @Test fun radioScansAreForegroundOnlyAndLowFrequency() {
        assertEquals(60_000L, WifiScanPolicy.AUTO_SCAN_INTERVAL_MS)
        assertFalse(WifiScanPolicy.shouldScan(true, true, false, true))
        assertFalse(WifiScanPolicy.shouldScan(true, false, true, true))
        assertTrue(WifiScanPolicy.shouldScan(true, true, true, true))
        val file = listOf(
            File("src/main/java/com/speedtester/app/WifiAnalyzerScreen.kt"),
            File("app/src/main/java/com/speedtester/app/WifiAnalyzerScreen.kt")
        ).first { it.exists() }
        val code = file.readText()
        assertTrue(code.contains("delay(WifiScanPolicy.AUTO_SCAN_INTERVAL_MS)"))
        assertTrue(code.contains("Pause periodic scans"))
        assertTrue(code.contains("Refresh Wi-Fi scan"))
    }
}
