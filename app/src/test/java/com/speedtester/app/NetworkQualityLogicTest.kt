package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test

class NetworkQualityLogicTest {
    private fun endpoint(service: String, code: Int?, latency: Long? = 42, count: Int = 1,
                         succeeded: Int = if (code in 200..399) 1 else 0) =
        QualityEndpoint(service, "example.com", latency, code, count, succeeded)

    @Test fun unavailableDoesNotInventScore() {
        val sample = NetworkQualityLogic.evaluate(listOf(null, null))
        assertNull(sample.grade)
        assertEquals(0, sample.successes)
        assertEquals("Unavailable", NetworkQualityLogic.label(sample.grade))
        assertTrue(QualityReportLogic.overall(sample, listOf(endpoint("Internet", 204)))!! <= 79)
    }

    @Test fun stableLowLatencyScoresHigh() {
        val sample = NetworkQualityLogic.evaluate(listOf(10L, 11L, 10L, 12L))
        assertTrue(sample.grade!! >= 90)
        assertEquals(4, sample.successes)
        assertNotNull(sample.jitterMs)
    }

    @Test fun failedConnectionsPenalized() {
        val good = NetworkQualityLogic.evaluate(listOf(20L, 20L, 20L))
        val bad = NetworkQualityLogic.evaluate(listOf(20L, null, 20L))
        assertTrue(bad.grade!! < good.grade!!)
        assertTrue(bad.note.contains("not measure packet loss"))
    }

    @Test fun gradeAlwaysBounded() {
        assertEquals(0, NetworkQualityLogic.evaluate(listOf(99999L)).grade)
        assertEquals(100, NetworkQualityLogic.evaluate(listOf(1L, 1L, 1L)).grade)
    }

    @Test fun missingTcpTargetUsesConservativeHttpsOnlyScore() {
        val unavailable = NetworkQualityLogic.evaluate(listOf(null, null, null))
        val score = QualityReportLogic.overall(unavailable, listOf(endpoint("Internet", 204)))
        assertNotNull(score)
        assertTrue(score!! <= 79)
    }

    @Test fun jitterDoesNotBridgeFailedMeasurements() {
        val result = NetworkQualityLogic.evaluate(listOf(10L, null, 100L))
        assertNull(result.jitterMs)
    }

    @Test fun noHttpsSuccessMeansNoOverallRating() {
        val tcp = NetworkQualityLogic.evaluate(listOf(12L, 13L, 11L))
        assertNull(QualityReportLogic.overall(tcp, emptyList()))
        assertNull(QualityReportLogic.overall(tcp, listOf(endpoint("Browsing", null))))
        assertNull(QualityReportLogic.overall(tcp, listOf(endpoint("Gaming", 403))))
    }

    @Test fun reachableEndpointsProduceBoundedQualityEstimate() {
        val tcp = NetworkQualityLogic.evaluate(listOf(14L, 15L, 16L))
        val score = QualityReportLogic.overall(tcp,
            listOf(endpoint("Internet", 204), endpoint("Browsing", 200)))!!
        assertTrue(score in 0..100)
        assertTrue(score >= 85)
    }

    @Test fun failingHttpsProbeReducesScore() {
        val tcp = NetworkQualityLogic.evaluate(listOf(20L, 20L, 20L))
        val healthy = QualityReportLogic.overall(tcp, listOf(endpoint("Internet", 204), endpoint("Browsing", 200)))!!
        val partial = QualityReportLogic.overall(tcp, listOf(endpoint("Internet", 204), endpoint("Browsing", null)))!!
        assertTrue(partial < healthy)
    }

    @Test fun slowEndpointAffectsScore() {
        val tcp = NetworkQualityLogic.evaluate(listOf(14L, 15L, 16L))
        val fast = QualityReportLogic.overall(tcp, listOf(endpoint("Internet", 200, 30)))!!
        val slow = QualityReportLogic.overall(tcp, listOf(endpoint("Internet", 200, 1000)))!!
        assertTrue(slow < fast)
    }

    @Test fun http4xxIsRespondedButNotUsable() {
        val restricted = endpoint("Gaming", 403)
        assertTrue(restricted.responded)
        assertFalse(restricted.usable)
        assertEquals("HTTP 403", restricted.status)
    }

    @Test fun findingsWarnAboutSignalAndConnectionFailureWithoutInventingPacketLoss() {
        val access = QualityAccess("Wi-Fi", -81, 2412, "192.168.1.1", false)
        val tcp = NetworkQualityLogic.evaluate(listOf(300L, null, 350L))
        val findings = QualityReportLogic.findings(access, tcp, listOf(endpoint("Internet", 503)))
        assertTrue(findings.any { it.text.contains("Weak Wi-Fi") })
        assertTrue(findings.any { it.text.contains("not a packet-loss measurement") })
        assertTrue(findings.any { it.text.contains("not validated") })
        assertTrue(findings.any { it.text.contains("website endpoints responded with restrictions") })
    }

    @Test fun optionalWebsiteRestrictionsDoNotPenalizeConnectionScore() {
        val tcp = NetworkQualityLogic.evaluate(listOf(15L, 14L, 16L))
        val core = listOf(endpoint("Internet", 204), endpoint("Browsing", 204))
        val baseline = QualityReportLogic.overall(tcp, core)
        val withRestrictions = QualityReportLogic.overall(tcp, core +
            listOf(endpoint("Gaming", 403), endpoint("WhatsApp", 429), endpoint("Streaming", 500)))
        assertEquals(baseline, withRestrictions)
    }

    @Test fun serverPolicyResponseDoesNotMeanInternetUnavailable() {
        val restricted = endpoint("Internet", 403)
        assertTrue(restricted.responded)
        assertFalse(restricted.usable)
        assertTrue(restricted.status.contains("restricted"))
        val tcp = NetworkQualityLogic.evaluate(listOf(15L, 16L, 16L))
        assertNotNull(QualityReportLogic.overall(tcp, listOf(restricted)))
    }

    @Test fun onlyCoreInternetTargetsDetermineReachability() {
        val tcp = NetworkQualityLogic.evaluate(listOf(15L, 16L, 16L))
        assertNull(QualityReportLogic.overall(tcp, listOf(endpoint("Gaming", 200), endpoint("WhatsApp", 204))))
        assertNull(QualityReportLogic.overall(tcp, listOf(endpoint("Internet", null), endpoint("Browsing", null))))
    }

    @Test fun partialEndpointRepliesAreNotAllFailures() {
        val tcp = NetworkQualityLogic.evaluate(listOf(15L, 16L, 16L))
        val half = QualityEndpoint("Internet", "example.net", 50L, 204, 2, 1, 1)
        val full = QualityEndpoint("Internet", "example.net", 50L, 204, 2, 2, 2)
        assertTrue(QualityReportLogic.overall(tcp, listOf(half))!! <
            QualityReportLogic.overall(tcp, listOf(full))!!)
    }

    @Test fun testProfilesHaveStrictBoundedProbeBudgets() {
        assertEquals(3, QualityProfile.QUICK.tcpSamples)
        assertTrue(QualityProfile.STANDARD.tcpSamples <= 5)
        assertTrue(QualityProfile.DETAILED.tcpSamples <= 8)
        QualityProfile.entries.forEach {
            assertTrue(it.httpsSamples in 1..2)
            assertTrue(it.tcpSamples + it.httpsSamples * 5 <= 18)
        }
    }
}
