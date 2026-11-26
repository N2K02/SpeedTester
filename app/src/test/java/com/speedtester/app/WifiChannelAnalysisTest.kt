package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test

class WifiChannelAnalysisTest {
    private fun ap(frequency: Int, level: Int = -45, width: Int = 20) =
        WifiAccessPoint("Test", "00:01:02:03:04:05", frequency, level, width, "[WPA2-PSK-CCMP][ESS]")

    @Test fun mapsAllThreeBandsAndSpecialChannels() {
        assertEquals(1, WifiChannelAnalysis.channelFor(2412))
        assertEquals(11, WifiChannelAnalysis.channelFor(2462))
        assertEquals(14, WifiChannelAnalysis.channelFor(2484))
        assertEquals(44, WifiChannelAnalysis.channelFor(5220))
        assertEquals(5, WifiChannelAnalysis.channelFor(5975))
        assertEquals(2, WifiChannelAnalysis.channelFor(5935))
        assertEquals(0, WifiChannelAnalysis.channelFor(0))
        assertEquals(WifiBand.SIX, WifiChannelAnalysis.bandFor(5975))
        assertNull(WifiChannelAnalysis.bandFor(7200))
    }
    @Test fun recommendationAvoidsStrongNearbyPoints() {
        val points = listOf(ap(2412, -35), ap(2417, -50), ap(2437, -40))
        val best = WifiChannelAnalysis.recommended(WifiBand.TWO_FOUR, points)
        assertEquals(11, best?.channel)
        assertEquals(0.0, best!!.congestion, 0.001)
    }
    @Test fun channelOverlapUsesRealMegahertzRatherThanChannelNumbers() {
        // In 5 GHz, channels 36 and 40 are 20 MHz apart, so two 20 MHz
        // channels should not be mistaken for overlapping frequencies.
        val neighbouring = listOf(ap(5180, -35, 20))
        val onChannel = WifiChannelAnalysis.rating(36, WifiBand.FIVE, neighbouring)
        val adjacent = WifiChannelAnalysis.rating(40, WifiBand.FIVE, neighbouring)
        assertTrue(onChannel.congestion > 0.0)
        assertEquals(0.0, adjacent.congestion, 0.00001)
        assertEquals(0, adjacent.affectedNetworks)
    }

    @Test fun wideChannelsCreateMeasuredOverlapAcrossBands() {
        assertTrue(WifiChannelAnalysis.rating(44, WifiBand.FIVE,
            listOf(ap(5180, -40, 80))).congestion > 0.0)
        assertEquals(5975, WifiChannelAnalysis.centerFrequencyMhz(5, WifiBand.SIX))
        assertEquals(2412, WifiChannelAnalysis.centerFrequencyMhz(1, WifiBand.TWO_FOUR))
        assertEquals(2484, WifiChannelAnalysis.centerFrequencyMhz(14, WifiBand.TWO_FOUR))
    }

    @Test fun strongerSignalsIncreaseCongestion() {
        val strong = WifiChannelAnalysis.rating(1, WifiBand.TWO_FOUR, listOf(ap(2412, -35)))
        val weak = WifiChannelAnalysis.rating(1, WifiBand.TWO_FOUR, listOf(ap(2412, -85)))
        assertTrue(strong.congestion > weak.congestion)
    }
    @Test fun widerNetworksInterfereWithAdjacentChannels() {
        val narrow = WifiChannelAnalysis.rating(44, WifiBand.FIVE, listOf(ap(5180, -40, 20)))
        val wide = WifiChannelAnalysis.rating(44, WifiBand.FIVE, listOf(ap(5180, -40, 80)))
        assertTrue(wide.congestion > narrow.congestion)
    }
    @Test fun filtersBandsAndNoUnobservedRecommendation() {
        val points = listOf(ap(2412), ap(5180), ap(5975))
        assertEquals(1, WifiChannelAnalysis.forBand(WifiBand.FIVE, points).size)
        assertNull(WifiChannelAnalysis.recommended(WifiBand.FIVE, listOf(ap(2412))))
    }
    @Test fun securityIdentifiesCommonStandards() {
        assertEquals("WPA3", WifiChannelAnalysis.securityFor("[RSN-SAE-CCMP]"))
        assertEquals("WPA2", WifiChannelAnalysis.securityFor("[WPA2-PSK-CCMP]"))
        assertEquals("Enhanced Open", WifiChannelAnalysis.securityFor("[OWE_TRANSITION]"))
        assertEquals("Open", WifiChannelAnalysis.securityFor("[ESS]"))
    }
}
