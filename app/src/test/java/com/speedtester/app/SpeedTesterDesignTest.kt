package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SpeedTesterDesignTest {
    private fun candidate(
        id: String, sponsor: String, city: String, ping: Long?, km: Double?
    ) = NdtServerCandidate(
        host = "speed-$id.example.net:8080", city = city, country = "MU",
        countryName = "Mauritius", downloadUrl = "https://speed-$id.example.net/random.jpg",
        uploadUrl = "https://speed-$id.example.net/upload.php",
        latencyMs = ping, distanceKm = km, serverId = id, sponsor = sponsor
    )

    @Test fun downloadAndUploadUseIndependentPhaseReadings() {
        assertNull(SpeedRatePresentation.shown(true, null, 0f, 50.0))
        assertEquals(24.8, SpeedRatePresentation.shown(true, null, 24.8f, 50.0)!!, 0.001)
        assertEquals(61.2, SpeedRatePresentation.shown(true, 61.2, 22f, 50.0)!!, 0.001)
        assertEquals(50.0, SpeedRatePresentation.shown(false, null, 22f, 50.0)!!, 0.001)
        assertEquals("—", SpeedRatePresentation.format(null))
        assertEquals("—", SpeedRatePresentation.format(Double.NaN))
        assertEquals("0.0", SpeedRatePresentation.format(0.0))
        assertEquals("102.7", SpeedRatePresentation.format(102.7))
    }

    @Test fun directoryFiltersAndSortsWithoutFabricatingLatency() {
        val servers = listOf(
            candidate("1", "Mauritius Telecom Ltd", "Rose Hill", 130L, 15.0),
            candidate("2", "Emtel Ltd", "Rodrigues", null, 600.0),
            candidate("3", "Mauritius Telecom Ltd", "Port Louis", 40L, 35.0),
            candidate("4", "Other Provider", "Floreal", 80L, 4.0)
        )
        assertEquals(listOf("3", "4", "1", "2"),
            ServerDirectoryLogic.visible(servers, "", ServerSortMode.LOWEST_TCP_PING).map { it.serverId })
        assertEquals(listOf("4", "1", "3", "2"),
            ServerDirectoryLogic.visible(servers, "", ServerSortMode.NEAREST).map { it.serverId })
        val recommended = ServerDirectoryLogic.visible(servers, "", ServerSortMode.RECOMMENDED)
        assertEquals(4, recommended.size)
        assertEquals("2", recommended.last().serverId)
        assertEquals(listOf("1", "3"),
            ServerDirectoryLogic.visible(servers, "Telecom", ServerSortMode.NEAREST).map { it.serverId })
        assertEquals("2", ServerDirectoryLogic.visible(servers, "emtel", ServerSortMode.LOWEST_TCP_PING).single().serverId)
        assertEquals("MT", ServerDirectoryLogic.initials("Mauritius Telecom Ltd"))
        assertEquals("E", ServerDirectoryLogic.initials("Emtel Ltd"))
        assertEquals("ST", ServerDirectoryLogic.initials(""))
    }

    @Test fun presentationKeepsGaugeAndUpdatesBothValuesWhileTesting() {
        val root = listOf(File("app/src/main/java/com/speedtester/app"),
            File("src/main/java/com/speedtester/app")).first { it.isDirectory }
        val main = File(root, "MainActivity.kt").readText()
        val gauge = File(root, "SpeedometerDial.kt").readText()
        val cards = File(root, "SpeedRateCards.kt").readText()
        val directory = File(root, "SpeedServerDirectory.kt").readText()
        assertTrue(main.contains("SpeedometerDial(gaugeSpeed, gaugeUpload, gaugeRunId)"))
        assertTrue(main.contains("SpeedRateCards("))
        assertTrue(main.contains("downloadPhaseMbps"))
        assertTrue(main.contains("uploadPhaseMbps"))
        assertTrue(main.contains("onPhaseFinished"))
        assertTrue(gauge.contains("Canvas(Modifier.fillMaxSize())"))
        assertFalse(gauge.contains("animatedSpeed"))
        assertTrue(cards.contains("RateCard(\"Download\""))
        assertTrue(cards.contains("RateCard(\"Upload\""))
        assertTrue(directory.contains("ServerSortMode.RECOMMENDED"))
        assertTrue(directory.contains("server.uploadUrl.startsWith(\"https://\""))
        assertTrue(directory.contains("RadioButton(selected = chosen"))
    }
}
