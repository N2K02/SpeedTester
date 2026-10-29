package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HomeDetailedMetricsTest {
    @Test fun speedPresentationHandlesMissingAndNonFiniteValues() {
        assertEquals("—", HomeMetricsFormatting.speed(null))
        assertEquals("—", HomeMetricsFormatting.speed(Double.NaN))
        assertEquals("—", HomeMetricsFormatting.speed(Double.POSITIVE_INFINITY))
        assertEquals("—", HomeMetricsFormatting.speed(-3.0))
        assertEquals("105.6", HomeMetricsFormatting.speed(105.55))
        assertEquals("0.0", HomeMetricsFormatting.speed(0.0))
    }

    @Test fun latencyAndWifiIdentityDoNotInventMeasurements() {
        assertEquals("Unavailable", HomeMetricsFormatting.latency(-1))
        assertEquals("214 ms", HomeMetricsFormatting.latency(214))
        assertEquals("Unavailable", HomeMetricsFormatting.identity(""))
        assertEquals("Unavailable", HomeMetricsFormatting.identity("—"))
        assertEquals("Unavailable", HomeMetricsFormatting.identity("Unavailable"))
        assertEquals("MyHomeWiFi", HomeMetricsFormatting.identity("MyHomeWiFi"))
        assertEquals("AA:BB:CC:DD:EE:FF", HomeMetricsFormatting.identity("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun mainDashboardDisplaysAutomaticGaugeAndDedicatedReadableMetrics() {
        val sourceRoot = listOf(
            File("app/src/main/java/com/speedtester/app"),
            File("src/main/java/com/speedtester/app")
        ).first { it.isDirectory }
        val main = File(sourceRoot, "MainActivity.kt").readText()
        val first = main.indexOf("0 -> LazyColumn(")
        val last = main.indexOf("1 -> SpeedTestHistoryScreen(", first)
        assertTrue(first >= 0 && last > first)
        val home = main.substring(first, last)
        assertTrue(home.contains("SpeedometerDial(gaugeSpeed, gaugeUpload, gaugeRunId)"))
        assertTrue(home.contains("if (phase.contains(\"Upload\", true))") ||
            main.contains("if (phase.contains(\"Upload\", true))"))
        assertTrue(home.contains("HomeDetailedMetrics("))
        assertTrue(home.contains("onToggle = { showExtendedMetrics = !showExtendedMetrics }"))
        assertFalse(home.contains("FilterChip("))
        assertTrue(main.contains("gaugeUpload = true"))
        assertTrue(main.contains("gaugeUpload = false"))
        val metrics = File(sourceRoot, "HomeDetailedMetrics.kt").readText()
        assertTrue(metrics.contains("HomeDetailLine(\"Wi-Fi name (SSID)\""))
        assertTrue(metrics.contains("HomeDetailLine(\"Access point (BSSID)\""))
        assertTrue(metrics.contains("SpeedMetricTile(\"↓ Download\""))
        assertTrue(metrics.contains("SpeedMetricTile(\"↑ Upload\""))
    }
}
