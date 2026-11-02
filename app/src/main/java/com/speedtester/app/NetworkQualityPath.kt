package com.speedtester.app

/** Path indicators report only observations the application actually made. */
enum class QualityPathState { SUCCESS, FAILED, TESTING, PENDING, UNMEASURED }

data class QualityPathNode(
    val id: String,
    val title: String,
    val detail: String,
    val state: QualityPathState
)

object NetworkQualityPathLogic {
    fun build(
        access: QualityAccess,
        report: QualityReport?,
        running: Boolean,
        stage: String
    ): List<QualityPathNode> {
        val connected = access.transport != "Disconnected"
        val accessState = if (connected) QualityPathState.SUCCESS else QualityPathState.FAILED
        val link = when (access.transport) {
            "Wi-Fi" -> "Wi-Fi"
            "Mobile data" -> "Mobile"
            "Ethernet" -> "Ethernet"
            "VPN" -> "VPN"
            else -> "Link"
        }
        val linkDetail = when {
            !connected -> "Offline"
            access.transport == "Wi-Fi" && access.signalDbm != null && access.signalDbm <= -75 -> "Weak"
            access.transport == "Wi-Fi" && access.frequencyMhz != null ->
                QualityReportLogic.wifiBand(access.frequencyMhz)
            else -> "Connected"
        }
        val endpoint = report?.endpoints?.firstOrNull { it.service == "Internet" }
        val internetState = when {
            endpoint != null && endpoint.responded -> QualityPathState.SUCCESS
            endpoint != null -> QualityPathState.FAILED
            !connected -> QualityPathState.FAILED
            running && stage.startsWith("Internet") -> QualityPathState.TESTING
            else -> QualityPathState.PENDING
        }
        val internetDetail = when (internetState) {
            QualityPathState.SUCCESS -> "Responded"
            QualityPathState.FAILED -> "No reply"
            QualityPathState.TESTING -> "Testing"
            else -> "Pending"
        }
        return listOf(
            QualityPathNode("device", "Device", if (connected) "Ready" else "Offline", accessState),
            QualityPathNode("link", link, linkDetail, accessState),
            // A gateway route's presence is not evidence that the router was probed.
            QualityPathNode("gateway", "Gateway",
                if (access.gateway != null) "Detected" else "Unknown", QualityPathState.UNMEASURED),
            QualityPathNode("isp", "ISP", "Not probed", QualityPathState.UNMEASURED),
            QualityPathNode("internet", "Internet", internetDetail, internetState)
        )
    }
}
