package com.speedtester.app

/** Active scans are allowed only while the analyzer is visible and the app is foregrounded. */
internal object WifiScanPolicy {
    // User-visible Wi-Fi analyzer only. Respect Android scan throttles and
    // avoid repeated radio wakeups on a static screen.
    const val AUTO_SCAN_INTERVAL_MS = 60_000L
    fun shouldScan(preciseGranted: Boolean, autoScanning: Boolean, foreground: Boolean, locationOn: Boolean): Boolean =
        preciseGranted && autoScanning && foreground && locationOn
}
