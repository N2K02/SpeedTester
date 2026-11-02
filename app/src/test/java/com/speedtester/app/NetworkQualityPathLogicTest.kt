package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test

class NetworkQualityPathLogicTest {
    private val wifi = QualityAccess("Wi-Fi", -55, 5200, "192.168.1.1", true)
    private val tcp = NetworkQualityLogic.evaluate(listOf(20L, 21L, 23L))
    private fun report(httpCode: Int?): QualityReport {
        val response = if (httpCode == null) 0 else 1
        return QualityReport(
            1234L, QualityProfile.STANDARD, wifi, tcp,
            listOf(QualityEndpoint("Internet", "example.com", 20, httpCode, 1,
                if (httpCode in 200..399) 1 else 0, response)),
            85, emptyList()
        )
    }

    @Test fun unmeasuredGatewayAndIspMustNeverAppearAsSuccessful() {
        val nodes = NetworkQualityPathLogic.build(wifi, report(204), false, "Complete")
        assertEquals(QualityPathState.UNMEASURED, nodes[2].state)
        assertEquals(QualityPathState.UNMEASURED, nodes[3].state)
        assertEquals("Detected", nodes[2].detail)
        assertEquals("Not probed", nodes[3].detail)
        assertEquals(QualityPathState.SUCCESS, nodes[4].state)
    }

    @Test fun internetEndpointRespondingWithRestrictionIsStillConnected() {
        val nodes = NetworkQualityPathLogic.build(wifi, report(403), false, "Complete")
        assertEquals(QualityPathState.SUCCESS, nodes.last().state)
        assertEquals("Responded", nodes.last().detail)
    }

    @Test fun lackOfHttpsResponseIsFailedNotGreen() {
        val nodes = NetworkQualityPathLogic.build(wifi, report(null), false, "Complete")
        assertEquals(QualityPathState.FAILED, nodes.last().state)
        assertEquals("No reply", nodes.last().detail)
    }

    @Test fun runningProbeIsVisibleAndDoesNotInventSuccess() {
        val nodes = NetworkQualityPathLogic.build(wifi, null, true, "Internet · 6/10")
        assertEquals(QualityPathState.TESTING, nodes.last().state)
        assertEquals(QualityPathState.UNMEASURED, nodes[2].state)
        assertEquals(QualityPathState.UNMEASURED, nodes[3].state)
    }

    @Test fun liveInternetResultTransitionsFromTestingToObservedStatus() {
        val testing = NetworkQualityPathLogic.build(wifi, null, true, "Internet · 5/10")
        assertEquals(QualityPathState.TESTING, testing.last().state)
        val responded = NetworkQualityPathLogic.build(wifi, null, true, "Browsing · 6/10", true)
        assertEquals(QualityPathState.SUCCESS, responded.last().state)
        val blocked = NetworkQualityPathLogic.build(wifi, null, true, "Browsing · 6/10", false)
        assertEquals(QualityPathState.FAILED, blocked.last().state)
    }

    @Test fun offlineAccessAndInternetShowFailure() {
        val offline = QualityAccess("Disconnected", null, null, null, false)
        val nodes = NetworkQualityPathLogic.build(offline, null, false, "Ready")
        assertEquals(QualityPathState.FAILED, nodes.first().state)
        assertEquals(QualityPathState.FAILED, nodes.last().state)
        assertEquals(QualityPathState.UNMEASURED, nodes[2].state)
    }

    @Test fun mobileTransportDoesNotInventWifiDetails() {
        val mobile = QualityAccess("Mobile data", null, null, null, true)
        val nodes = NetworkQualityPathLogic.build(mobile, null, false, "Ready")
        assertEquals("Mobile", nodes[1].title)
        assertEquals("Connected", nodes[1].detail)
    }
}
