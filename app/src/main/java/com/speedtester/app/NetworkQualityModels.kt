package com.speedtester.app

import kotlin.math.roundToInt

/** Quality estimates are based on lightweight TCP and HTTPS probes, not application workloads. */
enum class QualityProfile(val tcpSamples: Int, val httpsSamples: Int) {
    QUICK(3, 1),
    STANDARD(5, 1),
    DETAILED(8, 2)
}

data class QualityEndpoint(
    val service: String,
    val host: String,
    val latencyMs: Long?,
    val httpCode: Int?,
    val attempted: Int,
    val successful: Int
) {
    val responded: Boolean get() = httpCode != null
    val usable: Boolean get() = httpCode != null && httpCode in 200..399
    val status: String get() = when {
        usable -> "Reachable"
        responded -> "HTTP $httpCode"
        else -> "Unavailable"
    }
}

data class QualityAccess(
    val transport: String,
    val signalDbm: Int?,
    val frequencyMhz: Int?,
    val gateway: String?,
    val androidValidated: Boolean
)

data class QualityFinding(val positive: Boolean, val text: String)

data class QualityReport(
    val startedAt: Long,
    val profile: QualityProfile,
    val access: QualityAccess,
    val tcp: QualitySample,
    val endpoints: List<QualityEndpoint>,
    val score: Int?,
    val findings: List<QualityFinding>,
    val durationMs: Long = 0L,
    val processCpuMs: Long? = null,
    val appTrafficBytes: Long? = null
)

object QualityReportLogic {
    /** Never return a positive overall rating when HTTPS reachability was not demonstrated. */
    fun overall(tcp: QualitySample, endpoints: List<QualityEndpoint>): Int? {
        val reachable = endpoints.filter { it.usable }
        if (reachable.isEmpty()) return null
        val successes = endpoints.sumOf { it.successful }
        val attempts = endpoints.sumOf { it.attempted }
        if (attempts <= 0) return null
        val endpointReliability = 100.0 * successes / attempts
        // Penalize slow endpoints without declaring their actual service performance.
        val medianHttp = reachable.mapNotNull { it.latencyMs }.sorted()
            .let { if (it.isEmpty()) 0L else it[it.size / 2] }
        val responsiveness = (100.0 - (medianHttp - 100).coerceAtLeast(0) * .08).coerceIn(0.0, 100.0)
        return if (tcp.grade == null) {
            // Some networks block the selected TCP target even while HTTPS works.
            // Give a conservative, explicitly limited HTTPS-only estimate.
            (endpointReliability * .70 + responsiveness * .30).roundToInt().coerceIn(0, 79)
        } else {
            (tcp.grade * .60 + endpointReliability * .25 + responsiveness * .15)
                .roundToInt().coerceIn(0, 100)
        }
    }

    fun findings(access: QualityAccess, tcp: QualitySample, endpoints: List<QualityEndpoint>): List<QualityFinding> {
        val result = mutableListOf<QualityFinding>()
        if (!access.androidValidated) result += QualityFinding(false, "Android has not validated internet access on this network.")
        access.signalDbm?.let { if (it <= -75) result += QualityFinding(false, "Weak Wi-Fi signal ($it dBm). Move closer to the access point.") }
        if (tcp.grade == null) result += QualityFinding(false, "TCP probe target unavailable. Overall rating uses HTTPS-only measurements.")
        tcp.latencyMs?.let { if (it > 120) result += QualityFinding(false, "High TCP connection time (${it} ms).") }
        if (tcp.successes < tcp.attempts) result += QualityFinding(false,
            "${tcp.attempts - tcp.successes} TCP connection attempts failed; this is not a packet-loss measurement.")
        if (endpoints.isNotEmpty()) {
            val failed = endpoints.filterNot { it.usable }
            if (failed.isNotEmpty()) result += QualityFinding(false,
                "${failed.size} of ${endpoints.size} HTTPS endpoint checks failed or returned a non-success status.")
        }
        if (result.isEmpty() && endpoints.any { it.usable } && tcp.successes == tcp.attempts) {
            result += QualityFinding(true, "Tested TCP and HTTPS endpoints responded normally.")
        }
        result += QualityFinding(true, "Gateway presence and ISP path are informational; intermediate hops were not actively tested.")
        return result
    }
}
