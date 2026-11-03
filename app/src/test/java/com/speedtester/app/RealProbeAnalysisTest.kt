package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test

class RealProbeAnalysisTest {
    @Test fun fourKSuccessRequiresActualRenderedUhdAndPlaybackProgress() {
        val actual = RealProbeAnalysis.video(3840, 2160, true, 7_300, 1, 0, 8_000)
        assertEquals(RealProbeState.VERIFIED, actual.state)
        assertTrue(actual.detail.contains("actual UHD playback"))
        assertEquals(RealProbeState.UNAVAILABLE,
            RealProbeAnalysis.video(3840, 2160, false, 7_300, 0, 0, 8_000).state)
        assertEquals(RealProbeState.UNAVAILABLE,
            RealProbeAnalysis.video(3840, 2160, true, 2_500, 0, 0, 5_000).state)
    }

    @Test fun resolutionAndPlaybackStallsCannotBeReportedAsGood4k() {
        assertEquals(RealProbeState.DEGRADED,
            RealProbeAnalysis.video(1920, 1080, true, 7_300, 0, 0, 8_000).state)
        assertEquals(RealProbeState.DEGRADED,
            RealProbeAnalysis.video(3840, 2160, true, 7_300, 0, 2_000, 11_000).state)
        assertEquals(RealProbeState.DEGRADED,
            RealProbeAnalysis.video(3840, 2160, true, 7_300, 13, 0, 8_000).state)
    }

    @Test fun noUdpEchoCannotBeFabricatedAsOneHundredPercentPacketLoss() {
        val unavailable = RealProbeAnalysis.udp(12, 0, emptyList())
        assertEquals(RealProbeState.UNAVAILABLE, unavailable.state)
        assertTrue(unavailable.detail.contains("loss cannot be measured"))
        val complete = RealProbeAnalysis.udp(12, 12, List(12) { 20L })
        assertEquals(RealProbeState.VERIFIED, complete.state)
        assertTrue(complete.detail.contains("0.0% packet loss"))
        val incomplete = RealProbeAnalysis.udp(12, 9, List(9) { 35L })
        assertEquals(RealProbeState.DEGRADED, incomplete.state)
        assertTrue(incomplete.detail.contains("25.0% packet loss"))
    }

    @Test fun serverAcceptanceIsNotDeviceDeliveryWithoutActualReceiverAck() {
        val unknown = RealProbeAnalysis.message(5, 0, emptyList())
        assertEquals(RealProbeState.UNAVAILABLE, unknown.state)
        assertTrue(unknown.detail.contains("No receiver acknowledgements"))
        val delivered = RealProbeAnalysis.message(5, 5, listOf(25, 30, 40, 50, 100))
        assertEquals(RealProbeState.VERIFIED, delivered.state)
        assertTrue(delivered.detail.contains("acknowledged by the receiving device"))
        assertTrue(delivered.detail.contains("not WhatsApp delivery"))
        assertEquals(RealProbeState.DEGRADED,
            RealProbeAnalysis.message(5, 3, listOf(20, 30, 40)).state)
    }

    @Test fun realTestConfigurationIsOptInAndNeedsControlledEndpoints() {
        val default = RealProbeConfig()
        assertFalse(default.enabled)
        assertTrue(default.videoUrl.startsWith("https://"))
        assertEquals("", default.udpHost)
        assertEquals(0, default.udpPort)
        assertEquals("", default.messageRoom)
    }
}
