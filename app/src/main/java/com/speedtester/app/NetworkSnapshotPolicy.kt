package com.speedtester.app

/** Reject measurements that might combine traffic from different networks. */
internal object NetworkSnapshotPolicy {
    private fun known(value: String): Boolean =
        value.isNotBlank() && value != "Unavailable" && value != "—"

    fun changed(
        startType: String, endType: String,
        startSsid: String, endSsid: String,
        startBssid: String, endBssid: String,
    ): Boolean {
        if (startType != endType) return true
        if (startType != "Wi-Fi") return false
        if (known(startBssid) && known(endBssid) && !startBssid.equals(endBssid, true)) return true
        if (known(startSsid) && known(endSsid) && startSsid != endSsid) return true
        return false
    }
}
