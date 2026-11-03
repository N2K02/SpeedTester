package com.speedtester.app

/**
 * Distinct, explainable checks. An HTTPS response proves that a web endpoint responded; it
 * cannot prove video playback, game UDP latency, voice quality or message delivery.
 */
enum class QualityScenarioState { GOOD, CAUTION, INFORMATIONAL, UNKNOWN }

data class QualityScenarioResult(
    val id: String,
    val title: String,
    val state: QualityScenarioState,
    val detail: String
)

object QualityScenarioLogic {
    fun evaluate(report: QualityReport): List<QualityScenarioResult> {
        val access = report.access
        val tcp = report.tcp
        val endpoints = report.endpoints
        val result = mutableListOf<QualityScenarioResult>()
        fun add(id: String, title: String, state: QualityScenarioState, detail: String) {
            result += QualityScenarioResult(id, title, state, detail)
        }
        add("access", "Device connection",
            if (access.transport == "Disconnected") QualityScenarioState.CAUTION
            else QualityScenarioState.GOOD,
            access.transport + " · " + if (access.androidValidated) "Android validated internet"
                else "Android has not validated internet access")
        if (access.transport == "Wi-Fi") {
            val signal = access.signalDbm
            add("signal", "Wi-Fi signal",
                when {
                    signal == null -> QualityScenarioState.UNKNOWN
                    signal <= -75 -> QualityScenarioState.CAUTION
                    else -> QualityScenarioState.GOOD
                }, if (signal == null) "Signal unavailable"
                   else "$signal dBm · ${QualityReportLogic.wifiBand(access.frequencyMhz)}")
        } else {
            add("signal", "Radio signal", QualityScenarioState.INFORMATIONAL,
                "Wi-Fi signal assessment does not apply to ${access.transport}")
        }
        add("dns", "DNS resolution",
            when {
                report.dnsLookupMs == null -> QualityScenarioState.UNKNOWN
                report.dnsLookupMs > 250 -> QualityScenarioState.CAUTION
                else -> QualityScenarioState.GOOD
            }, report.dnsLookupMs?.let { "$it ms · measured during HTTPS calls" }
                ?: "No DNS lookup timing recorded (cached, failed, or skipped)")
        add("tls", "Secure connection (TLS)",
            when {
                report.tlsHandshakeMs == null -> QualityScenarioState.UNKNOWN
                report.tlsHandshakeMs > 800 -> QualityScenarioState.CAUTION
                else -> QualityScenarioState.GOOD
            }, report.tlsHandshakeMs?.let { "$it ms · HTTPS handshake" }
                ?: "TLS handshake timing unavailable")
        add("tcp", "TCP connection responsiveness",
            when {
                tcp.successes == 0 -> QualityScenarioState.UNKNOWN
                tcp.latencyMs != null && tcp.latencyMs > 120 -> QualityScenarioState.CAUTION
                else -> QualityScenarioState.GOOD
            }, tcp.latencyMs?.let { "$it ms median to the independent TCP probe target" }
                ?: "Independent TCP probe target did not respond")
        add("stability", "TCP connection consistency",
            when {
                tcp.attempts == 0 -> QualityScenarioState.UNKNOWN
                tcp.successes < tcp.attempts -> QualityScenarioState.CAUTION
                tcp.jitterMs != null && tcp.jitterMs > 40 -> QualityScenarioState.CAUTION
                tcp.jitterMs == null -> QualityScenarioState.UNKNOWN
                else -> QualityScenarioState.GOOD
            }, "${tcp.successes}/${tcp.attempts} TCP connections · timing variation " +
                (tcp.jitterMs?.let { "%.1f ms".format(java.util.Locale.US, it) } ?: "unavailable") +
                " · not UDP or ICMP packet loss")
        val generic = endpoints.filter { it.service == "Internet" || it.service == "Browsing" }
        val attempts = generic.sumOf { it.attempted }
        val responses = generic.sumOf { it.responses }
        add("https", "General HTTPS connectivity",
            when {
                attempts == 0 -> QualityScenarioState.UNKNOWN
                responses == 0 -> QualityScenarioState.CAUTION
                responses < attempts -> QualityScenarioState.CAUTION
                else -> QualityScenarioState.GOOD
            }, "$responses/$attempts Internet/browsing HTTPS requests received an HTTP response")
        if (!access.androidValidated && responses > 0) {
            add("validation", "Connectivity validation", QualityScenarioState.INFORMATIONAL,
                "HTTPS responded, but Android has not validated this network. A captive portal or validation restriction is possible, not confirmed.")
        }
        listOf("Streaming", "Gaming", "WhatsApp").forEach { name ->
            val service = endpoints.firstOrNull { it.service == name }
            add("site_${name.lowercase()}", "$name website",
                when {
                    service == null || !service.responded -> QualityScenarioState.UNKNOWN
                    service.usable -> QualityScenarioState.GOOD
                    else -> QualityScenarioState.INFORMATIONAL
                }, if (service == null) "Not tested"
                    else "${service.status} · ${service.responses}/${service.attempted} HTTPS responses" +
                        (service.latencyMs?.let { " · $it ms" } ?: "") +
                        " · not actual ${when (name) {
                            "Streaming" -> "playback"
                            "Gaming" -> "gameplay"
                            else -> "message delivery"
                        }}")
        }
        add("gateway", "Local gateway and ISP path", QualityScenarioState.INFORMATIONAL,
            if (access.gateway == null) "Default gateway not identified; hop not measured"
            else "Default gateway identified, but router and ISP hops were not probed")
        return result
    }
}
