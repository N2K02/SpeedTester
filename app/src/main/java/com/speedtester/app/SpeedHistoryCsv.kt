package com.speedtester.app

/** RFC 4180-style CSV export including the Wi-Fi identifiers captured for each test. */
internal object SpeedHistoryCsv {
    private val header = listOf(
        "date", "download_mbps", "upload_mbps", "download_average_mbps", "download_peak_mbps",
        "upload_average_mbps", "upload_peak_mbps", "ping_ms", "jitter_ms", "loaded_ping_ms",
        "loaded_jitter_ms", "failed_tcp_probes", "total_tcp_probes", "server", "network_type",
        "internet_available", "wifi_signal", "wifi_quality", "wifi_link_speed", "wifi_frequency",
        "connection_note", "discovery_status", "discovery_ms", "wifi_ssid", "wifi_bssid"
    )
    private fun field(value: String): String {
        // Spreadsheet software can treat an untrusted SSID/server name as a formula.
        val safe = if (value.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    fun export(history: List<TestResult>): String = buildString {
        append(header.joinToString(",")).append("\r\n")
        history.forEach { item ->
            append(listOf(
                field(item.date), item.download.toString(), item.upload.toString(),
                item.downloadAverage.toString(), item.downloadPeak.toString(),
                item.uploadAverage.toString(), item.uploadPeak.toString(),
                item.ping.toString(), item.jitter.toString(), item.loadedPing.toString(),
                item.loadedJitter.toString(), item.failedProbes.toString(), item.probeCount.toString(),
                field(item.server), field(item.networkType), item.internetAvailable.toString(),
                field(item.wifiSignal), field(item.wifiQuality), field(item.wifiLink),
                field(item.wifiFrequency), field(item.connectionNote), field(item.discoveryStatus),
                item.discoveryMs.toString(), field(item.wifiSsid), field(item.wifiBssid)
            ).joinToString(",")).append("\r\n")
        }
    }
}
