package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReleaseReadinessTest {
    private fun source(name: String): String =
        listOf(File("app/src/main/java/com/speedtester/app/$name"),
            File("src/main/java/com/speedtester/app/$name")).first { it.isFile }.readText()

    @Test fun permissionSubmenuAndHomeRecoveryExist() {
        val main = source("MainActivity.kt")
        val page = source("SpeedTesterPermissionsScreen.kt")
        assertTrue(main.contains("activeTab == 9 -> \"Permissions\""))
        assertTrue(main.contains("9 -> SpeedTesterPermissionsScreen("))
        assertTrue(main.contains("headlineContent = { Text(\"Permissions\") }"))
        assertTrue(main.contains("Text(\"Manage Wi-Fi permissions\")"))
        assertTrue(page.contains("RequestMultiplePermissions()"))
        assertTrue(page.contains("Manifest.permission.ACCESS_FINE_LOCATION"))
        assertTrue(page.contains("ACTION_APPLICATION_DETAILS_SETTINGS"))
        assertTrue(page.contains("Lifecycle.Event.ON_RESUME"))
    }

    @Test fun backgroundWifiScanningIsLifecycleBound() {
        val wifi = source("WifiAnalyzerScreen.kt")
        assertTrue(wifi.contains("Lifecycle.Event.ON_STOP"))
        assertTrue(wifi.contains("DisposableEffect(locationGranted, inForeground, wifi)"))
        assertTrue(wifi.contains("LaunchedEffect(locationGranted, running, inForeground)"))
        assertTrue(wifi.contains("WifiScanPolicy.shouldScan("))
        assertTrue(WifiScanPolicy.shouldScan(true, true, true, true))
        assertFalse(WifiScanPolicy.shouldScan(true, true, false, true))
        assertFalse(WifiScanPolicy.shouldScan(false, true, true, true))
        assertFalse(WifiScanPolicy.shouldScan(true, false, true, true))
        assertFalse(WifiScanPolicy.shouldScan(true, true, true, false))
    }

    @Test fun networkSwitchesInvalidateMixedResults() {
        assertFalse(NetworkSnapshotPolicy.changed("Wi-Fi", "Wi-Fi", "home", "home", "AA:11", "aa:11"))
        assertTrue(NetworkSnapshotPolicy.changed("Wi-Fi", "Mobile data", "home", "Unavailable", "AA", "Unavailable"))
        assertTrue(NetworkSnapshotPolicy.changed("Wi-Fi", "Wi-Fi", "home", "other", "Unavailable", "Unavailable"))
        assertTrue(NetworkSnapshotPolicy.changed("Wi-Fi", "Wi-Fi", "home", "home", "AA", "BB"))
        assertFalse(NetworkSnapshotPolicy.changed("Wi-Fi", "Wi-Fi", "Unavailable", "Unavailable", "Unavailable", "Unavailable"))
        val main = source("MainActivity.kt")
        assertTrue(main.contains("registerDefaultNetworkCallback(networkCallback)"))
        assertTrue(main.contains("unregisterNetworkCallback(networkCallback)"))
        assertTrue(main.contains("Network changed — test not saved"))
        assertTrue(main.contains("testNetworkAtStart.type"))
    }

    @Test fun persistentHistoryNeverPublishesFailedWrites() {
        var live = listOf(TestResult("old", 1.0, 1.0, 10, 1.0, "host"))
        val next = listOf(TestResult("new", 2.0, 2.0, 20, 2.0, "host"))
        val failed = HistoryCommitPolicy.commit(next, { throw java.io.IOException("Disk full") }, { live = it })
        assertFalse(failed)
        assertEquals("old", live.first().date)
        assertTrue(HistoryCommitPolicy.commit(next, {}, { live = it }))
        assertEquals("new", live.first().date)
        val entry = next.first().copy(wifiSsid = "My \"Wi-Fi\"", wifiBssid = "AA:BB")
        assertEquals(entry, HistoryJsonCodec.decode(HistoryJsonCodec.encode(listOf(entry)).toString()).single())
        val legacy = HistoryJsonCodec.decode("""[{"date":"yesterday","download":10,"upload":1,"ping":25,"jitter":2,"server":"old"}]""").single()
        assertEquals("Unavailable", legacy.wifiSsid)
        assertEquals("Unavailable", legacy.wifiBssid)
        val main = source("MainActivity.kt")
        assertTrue(main.contains("persistHistory((listOf(saved) + history).take(100))"))
        assertTrue(main.contains("persistHistory(emptyList())"))
        assertTrue(main.contains("var selectedHistory by remember"))
        assertTrue(main.contains("Toast.makeText(context"))
        assertFalse(main.contains("history.remove(result)"))
    }

    @Test fun ciValidatesMinifiedReleaseBuildAsWellAsDebug() {
        val workflow = listOf(File(".github/workflows/build.yml"), File("../.github/workflows/build.yml"))
            .first { it.isFile }.readText()
        assertTrue(workflow.contains("testDebugUnitTest assembleDebug assembleRelease"))
    }
}
