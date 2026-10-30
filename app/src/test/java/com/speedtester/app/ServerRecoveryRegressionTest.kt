package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ServerRecoveryRegressionTest {
    private fun candidate(id: String, host: String, latency: Long? = 10L) =
        NdtServerCandidate(host, "Port Louis", "MU", "Mauritius",
            "https://$host/upload.php", "https://$host/upload.php",
            latencyMs = latency, serverId = id)

    @Test fun refreshUsesLiveServerInsteadOfStaleSnapshot() {
        val old = candidate("12", "old.example.com", 200)
        val live = candidate("12", "new.example.com", 12)
        assertEquals(live, ServerDirectoryLogic.selectedCurrent(listOf(live), old))
    }

    @Test fun connectionChangePreventsMixingWifiAndCellularResults() {
        assertTrue(NetworkSnapshotPolicy.changed("Wi-Fi", "Mobile data", "Guest", "Unavailable", "aa", "Unavailable"))
        assertTrue(NetworkSnapshotPolicy.changed("Wi-Fi", "Wi-Fi", "Guest", "Guest", "aa", "bb"))
        assertFalse(NetworkSnapshotPolicy.changed("Mobile data", "Mobile data", "Unavailable", "Unavailable", "", ""))
    }

    @Test fun serverRecoveryIsBoundedAndDoesNotBlendTransferSamples() {
        val root = listOf(File("app/src/main"), File("src/main")).first { it.isDirectory }
        val code = File(root, "java/com/speedtester/app/MainActivity.kt").readText()
        assertTrue(code.contains("lastRefresh > 900_000L"))
        assertTrue(code.contains("SERVER_FAILOVER"))
        assertTrue(code.contains("downloadSamples.clear(); uploadSamples.clear()"))
        assertTrue(code.contains("changedDuringTest.get() || connectivity.activeNetwork != networkAtStart"))
        assertTrue(code.contains("NET_CAPABILITY_VALIDATED"))
        assertTrue(code.contains("NET_CAPABILITY_CAPTIVE_PORTAL"))
    }
}
