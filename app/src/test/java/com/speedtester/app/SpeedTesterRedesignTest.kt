package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SpeedTesterRedesignTest {
    private fun result(
        date: String, network: String, down: Double = 10.0, ping: Long = 18L
    ) = TestResult(date, down, 12.0, ping, 1.0,
        "Server \"A\", Test", networkType = network, wifiSsid = "Home,\"WiFi\"",
        wifiBssid = "ac:bd:ce:00:11:22")

    @Test fun filtersWifiAndMobileFromStoredSnapshot() {
        val history = listOf(result("2026-10-29 20:00", "Wi-Fi"),
            result("2026-10-28 20:00", "Mobile data"))
        assertEquals(2, HistoryViewLogic.visible(history, HistoryFilter.ALL, HistoryOrder.LATEST).size)
        assertEquals(1, HistoryViewLogic.visible(history, HistoryFilter.WIFI, HistoryOrder.LATEST).size)
        assertEquals("Mobile data", HistoryViewLogic.visible(history,
            HistoryFilter.MOBILE, HistoryOrder.LATEST).single().networkType)
    }

    @Test fun sortingIgnoresInvalidPingAndKeepsChronology() {
        val history = listOf(result("2026-10-27 11:00", "Wi-Fi", 5.0, -1L),
            result("2026-10-29 11:00", "Wi-Fi", 99.0, 20L),
            result("2026-10-28 11:00", "Wi-Fi", 45.0, 5L))
        assertEquals("2026-10-29 11:00", HistoryViewLogic.visible(history,
            HistoryFilter.ALL, HistoryOrder.LATEST).first().date)
        assertEquals(99.0, HistoryViewLogic.visible(history,
            HistoryFilter.ALL, HistoryOrder.FASTEST).first().download, 0.0)
        assertEquals(5L, HistoryViewLogic.visible(history,
            HistoryFilter.ALL, HistoryOrder.LOWEST_PING).first().ping)
    }

    @Test fun csvExportsEscapedSsidAndBssid() {
        val text = SpeedHistoryCsv.export(listOf(result("2026-10-29 20:00", "Wi-Fi")))
        assertTrue(text.contains("wifi_ssid,wifi_bssid"))
        assertTrue(text.contains("\"Home,\"\"WiFi\"\"\""))
        assertTrue(text.contains("\"ac:bd:ce:00:11:22\""))
        assertTrue(text.contains("\"Server \"\"A\"\", Test\""))
        assertEquals(2, text.split("\r\n").filter { it.isNotEmpty() }.size)
    }

    @Test fun csvNeutralizesSpreadsheetFormulaInSsid() {
        val source = result("2026-10-29 20:00", "Wi-Fi").copy(wifiSsid = "=HYPERLINK(\"bad\")")
        assertTrue(SpeedHistoryCsv.export(listOf(source)).contains("\"'=HYPERLINK"))
    }

    @Test fun nearbyWifiCardsExposeSsidAndBssidWithoutOpeningDetails() {
        val p = listOf(File("app/src/main/java/com/speedtester/app"),
            File("src/main/java/com/speedtester/app")).first { it.isDirectory }
        val screen = File(p, "WifiAnalyzerScreen.kt").readText()
        val listStart = screen.indexOf("WifiPanel.NETWORKS ->")
        val listEnd = screen.indexOf("WifiPanel.BEST ->", listStart)
        assertTrue(listStart >= 0 && listEnd > listStart)
        val listUi = screen.substring(listStart, listEnd)
        assertTrue(listUi.contains("Text(ap.ssid"))
        assertTrue(listUi.contains("Text(\"BSSID: \" + ap.bssid.ifBlank"))
        assertTrue(listUi.contains("Text(\"Ch \" + ap.channel"))
    }

    @Test fun screenshotApprovedScreensPreserveNavigation() {
        val p = listOf(File("app/src/main/java/com/speedtester/app"),
            File("src/main/java/com/speedtester/app")).first { it.isDirectory }
        val main = File(p, "MainActivity.kt").readText()
        val history = File(p, "SpeedTestHistoryScreen.kt").readText()
        assertTrue(main.contains("SpeedometerDial(gaugeSpeed"))
        assertTrue(main.contains("6 -> SpeedServerDirectory("))
        assertTrue(main.contains("7 -> LazyColumn("))
        assertTrue(main.contains("8 -> {"))
        assertTrue(main.contains("netInfo.ssid"))
        assertTrue(main.contains("testNetworkAtStart.ssid"))
        assertTrue(main.contains("wifiSsid"))
        assertTrue(history.contains("HistoryFilter.WIFI"))
        assertTrue(history.contains("DetailLine(\"Wi-Fi name (SSID)\""))
        assertTrue(history.contains("DetailLine(\"Access point (BSSID)\""))
    }
}
