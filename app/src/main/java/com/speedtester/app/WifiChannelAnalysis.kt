package com.speedtester.app

import kotlin.math.abs

/** One measured access point (BSSID). No geolocation or synthetic data. */
internal data class WifiAccessPoint(
    val ssid: String,
    val bssid: String,
    val frequencyMhz: Int,
    val rssiDbm: Int,
    val widthMhz: Int,
    val capabilities: String,
) {
    val band: WifiBand? get() = WifiChannelAnalysis.bandFor(frequencyMhz)
    val channel: Int get() = WifiChannelAnalysis.channelFor(frequencyMhz)
    val security: String get() = WifiChannelAnalysis.securityFor(capabilities)
}

internal enum class WifiBand(val label: String) {
    TWO_FOUR("2.4"), FIVE("5"), SIX("6")
}

internal data class WifiChannelRating(
    val channel: Int,
    val congestion: Double,
    val affectedNetworks: Int,
)

internal object WifiChannelAnalysis {
    fun bandFor(frequencyMhz: Int): WifiBand? = when (frequencyMhz) {
        in 2400..2500 -> WifiBand.TWO_FOUR
        in 4900..5924 -> WifiBand.FIVE
        in 5925..7125 -> WifiBand.SIX
        else -> null
    }
    fun channelFor(frequencyMhz: Int): Int = when {
        frequencyMhz == 2484 -> 14
        frequencyMhz in 2412..2472 -> (frequencyMhz - 2407) / 5
        frequencyMhz in 4900..5924 -> (frequencyMhz - 5000) / 5
        frequencyMhz == 5935 -> 2
        frequencyMhz in 5955..7115 -> (frequencyMhz - 5950) / 5
        else -> 0
    }
    fun securityFor(capabilities: String): String {
        val caps = capabilities.uppercase()
        return when {
            "SAE" in caps || "WPA3" in caps -> "WPA3"
            "OWE" in caps -> "Enhanced Open"
            "WPA2" in caps || "RSN" in caps -> "WPA2"
            "WPA" in caps -> "WPA"
            "WEP" in caps -> "WEP"
            else -> "Open"
        }
    }
    fun candidatesFor(band: WifiBand): List<Int> = when (band) {
        WifiBand.TWO_FOUR -> listOf(1, 6, 11)
        WifiBand.FIVE -> listOf(36, 40, 44, 48, 149, 153, 157, 161)
        // Preferred scanning channel centres for 80 MHz 6 GHz blocks.
        WifiBand.SIX -> (5..229 step 16).toList()
    }
    fun centerFrequencyMhz(channel: Int, band: WifiBand): Int = when (band) {
        WifiBand.TWO_FOUR -> if (channel == 14) 2484 else 2407 + channel * 5
        WifiBand.FIVE -> 5000 + channel * 5
        WifiBand.SIX -> if (channel == 2) 5935 else 5950 + channel * 5
    }

    fun rating(channel: Int, band: WifiBand, points: List<WifiAccessPoint>): WifiChannelRating {
        var affected = 0
        var congestion = 0.0
        for (point in points.filter { it.band == band && it.channel > 0 }) {
            // Channel numbers have different frequency spacing in 2.4/5/6 GHz.
            // Compare actual spectral overlap, not channel-number distance: adjacent
            // 5 GHz 20 MHz channels differ by 4 numbers, but do not overlap.
            val candidateCenter = centerFrequencyMhz(channel, band)
            val pointWidth = point.widthMhz.coerceIn(20, 320).toDouble()
            val halfSpan = (20.0 + pointWidth) / 2.0
            val separation = abs(candidateCenter - point.frequencyMhz).toDouble()
            val proximity = (1.0 - separation / halfSpan).coerceIn(0.0, 1.0)
            if (proximity <= 0.0) continue
            affected++
            val strength = (point.rssiDbm + 100).coerceIn(5, 75) / 75.0
            congestion += strength * strength * proximity
        }
        return WifiChannelRating(channel, congestion, affected)
    }
    fun ratings(band: WifiBand, points: List<WifiAccessPoint>): List<WifiChannelRating> =
        candidatesFor(band).map { rating(it, band, points) }
    fun recommended(band: WifiBand, points: List<WifiAccessPoint>): WifiChannelRating? =
        if (points.none { it.band == band }) null
        else ratings(band, points).minWithOrNull(compareBy<WifiChannelRating> { it.congestion }.thenBy { it.channel })
    fun forBand(band: WifiBand, points: List<WifiAccessPoint>): List<WifiAccessPoint> =
        points.filter { it.band == band && it.channel > 0 }.sortedByDescending { it.rssiDbm }
}
