package com.speedtester.app

import org.json.JSONArray
import org.json.JSONObject

/** One serialization path for new, removed and migrated history records. */
internal object HistoryJsonCodec {
    private fun Double.safe() = if (isFinite()) this else 0.0

    fun decode(json: String): List<TestResult> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            TestResult(
                o.optString("date"), o.optDouble("download").safe(), o.optDouble("upload").safe(),
                o.optLong("ping", -1), o.optDouble("jitter").safe(), o.optString("server"),
                o.optDouble("downloadAverage", 0.0).safe(), o.optDouble("downloadPeak", 0.0).safe(),
                o.optDouble("uploadAverage", 0.0).safe(), o.optDouble("uploadPeak", 0.0).safe(),
                o.optLong("loadedPing", -1), o.optDouble("loadedJitter", 0.0).safe(),
                o.optInt("failedProbes"), o.optInt("probeCount"),
                o.optString("networkType", "Unknown"), o.optBoolean("internetAvailable", false),
                o.optString("wifiSignal", "—"), o.optString("wifiQuality", "—"),
                o.optString("wifiLink", "—"), o.optString("wifiFrequency", "—"),
                o.optString("connectionNote", ""),
                o.optString("discoveryStatus", "Not recorded"), o.optLong("discoveryMs", -1),
                o.optString("wifiSsid", "Unavailable"), o.optString("wifiBssid", "Unavailable")
            )
        }
    }

    fun encode(results: List<TestResult>): JSONArray = JSONArray().also { array ->
        results.forEach { x ->
            array.put(JSONObject()
                .put("date", x.date).put("download", x.download.safe()).put("upload", x.upload.safe())
                .put("ping", x.ping).put("jitter", x.jitter.safe()).put("server", x.server)
                .put("downloadAverage", x.downloadAverage.safe()).put("downloadPeak", x.downloadPeak.safe())
                .put("uploadAverage", x.uploadAverage.safe()).put("uploadPeak", x.uploadPeak.safe())
                .put("loadedPing", x.loadedPing).put("loadedJitter", x.loadedJitter.safe())
                .put("failedProbes", x.failedProbes).put("probeCount", x.probeCount)
                .put("networkType", x.networkType).put("internetAvailable", x.internetAvailable)
                .put("wifiSignal", x.wifiSignal).put("wifiQuality", x.wifiQuality)
                .put("wifiLink", x.wifiLink).put("wifiFrequency", x.wifiFrequency)
                .put("connectionNote", x.connectionNote).put("discoveryStatus", x.discoveryStatus)
                .put("discoveryMs", x.discoveryMs).put("wifiSsid", x.wifiSsid)
                .put("wifiBssid", x.wifiBssid))
        }
    }
}

/** Publish the updated in-memory list only after a successful durable write. */
internal object HistoryCommitPolicy {
    fun commit(next: List<TestResult>, write: (List<TestResult>) -> Unit, publish: (List<TestResult>) -> Unit): Boolean {
        try {
            write(next)
        } catch (_: Exception) {
            return false
        }
        publish(next)
        return true
    }
}
