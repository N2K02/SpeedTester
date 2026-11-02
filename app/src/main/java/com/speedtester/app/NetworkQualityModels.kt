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
    val successful: Int,
    // Any HTTP response proves HTTPS transport reachability, even if the host denies automation.
    val responses: Int = if (httpCode == null) 0 else attempted
) {
    val responded: Boolean get() = responses > 0
    val usable: Boolean get() = successful > 0
    val status: String get() = when {
        usable -> "Reachable"
        !responded -> "No response"
        httpCode in 400..499 -> "HTTP $httpCode (restricted)"
        httpCode in 500..599 -> "HTTP $httpCode (server error)"
        else -> "HTTP $httpCode"
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
    /** Only generic Internet/browsing connectivity affects the score.
     * Gaming/streaming/messaging sites may reject bots or HEAD/GET probes for policy reasons.
     */
    fun overall(tcp: QualitySample, endpoints: List<QualityEndpoint>): Int? {
        val generic = endpoints.filter { it.service == "Internet" || it.service == "Browsing" }
        val attempts = generic.sumOf { it.attempted }
        val responses = generic.sumOf { it.responses }
        if (attempts <= 0 || responses <= 0) return null
        val endpointReliability = 100.0 * responses.coerceAtMost(attempts) / attempts
        // This is HTTPS response latency, which includes DNS/TLS and is not gaming latency.
        val timings = generic.filter { it.responded }.mapNotNull { it.latencyMs }.sorted()
        val medianHttp = timings.getOrNull(timings.size / 2) ?: return null
        val responsiveness = (100.0 - (medianHttp - 100).coerceAtLeast(0) * .08)
            .coerceIn(0.0, 100.0)
        return if (tcp.grade == null) {
            // Network policy may block the independent TCP target even when HTTPS works.
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
            val noResponse = endpoints.filterNot { it.responded }
            if (noResponse.isNotEmpty()) result += QualityFinding(false,
                "${noResponse.size} of ${endpoints.size} HTTPS endpoints did not respond.")
            val restricted = endpoints.filter { it.responded && !it.usable }
            if (restricted.isNotEmpty()) result += QualityFinding(true,
                "${restricted.size} website endpoints responded with restrictions or server errors; this alone does not indicate an ISP fault.")
        }
        if (result.isEmpty() && endpoints.any { it.responded } && tcp.successes == tcp.attempts) {
            result += QualityFinding(true, "Tested TCP and HTTPS endpoints responded normally.")
        }
        result += QualityFinding(true, "Gateway and ISP segments are informational, not actively measured hops.")
        return result
    }
}
