package com.speedtester.app

/** Active scans are allowed only while the analyzer is visible and the app is foregrounded. */
internal object WifiScanPolicy {
    fun shouldScan(preciseGranted: Boolean, autoScanning: Boolean, foreground: Boolean, locationOn: Boolean): Boolean =
        preciseGranted && autoScanning && foreground && locationOn
}
