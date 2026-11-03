package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test

class NetworkQualityScenariosTest {
    private fun report(
        access: QualityAccess = QualityAccess("Wi-Fi", -55, 5180, "192.168.1.1", true),
        tcp: QualitySample = NetworkQualityLogic.evaluate(listOf(16L, 17L, 18L)),
        dns: Long? = 28L,
        tls: Long? = 95L,
        endpoints: List<QualityEndpoint> = listOf(
            QualityEndpoint("Internet", "example.com", 80L, 200, 1, 1),
            QualityEndpoint("Browsing", "example.org", 90L, 204, 1, 1),
            QualityEndpoint("Streaming", "stream.example", 100L, 403, 1, 0),
            QualityEndpoint("Gaming", "game.example", null, null, 1, 0),
            QualityEndpoint("WhatsApp", "chat.example", 80L, 200, 1, 1)
        )
    ) = QualityReport(System.currentTimeMillis(), QualityProfile.STANDARD, access, tcp,
        endpoints, 80, emptyList(), dnsLookupMs = dns, tlsHandshakeMs = tls)

    private fun List<QualityScenarioResult>.scenario(id: String) = first { it.id == id }

    @Test fun healthyMeasuredScenariosAreReportedAsGood() {
        val scenarios = QualityScenarioLogic.evaluate(report())
        assertEquals(QualityScenarioState.GOOD, scenarios.scenario("access").state)
        assertEquals(QualityScenarioState.GOOD, scenarios.scenario("signal").state)
        assertEquals(QualityScenarioState.GOOD, scenarios.scenario("dns").state)
        assertEquals(QualityScenarioState.GOOD, scenarios.scenario("tls").state)
        assertEquals(QualityScenarioState.GOOD, scenarios.scenario("https").state)
        assertEquals(QualityScenarioState.GOOD, scenarios.scenario("stability").state)
    }

    @Test fun independentFailureScenariosAreNotConflatedWithPacketLoss() {
        val sample = NetworkQualityLogic.evaluate(listOf(400L, null, 300L))
        val scenarios = QualityScenarioLogic.evaluate(report(
            access = QualityAccess("Wi-Fi", -87, 2412, "192.168.1.1", false),
            tcp = sample, dns = 500L, tls = 1200L,
            endpoints = listOf(QualityEndpoint("Internet", "example.com", null, null, 1, 0))))
        for (id in listOf("signal", "dns", "tls", "tcp", "stability", "https")) {
            assertEquals(id, QualityScenarioState.CAUTION, scenarios.scenario(id).state)
        }
        assertTrue(scenarios.scenario("stability").detail.contains("not UDP or ICMP packet loss"))
        assertEquals(QualityScenarioState.INFORMATIONAL, scenarios.scenario("gateway").state)
    }

    @Test fun blockedWebsiteIsInformationalNotGameQualityFailure() {
        val scenarios = QualityScenarioLogic.evaluate(report())
        assertEquals(QualityScenarioState.INFORMATIONAL, scenarios.scenario("site_streaming").state)
        assertEquals(QualityScenarioState.UNKNOWN, scenarios.scenario("site_gaming").state)
        assertTrue(scenarios.scenario("site_streaming").detail.contains("not actual playback"))
        assertEquals(QualityScenarioState.GOOD, scenarios.scenario("site_whatsapp").state)
        assertTrue(scenarios.scenario("site_whatsapp").detail.contains("not actual message delivery"))
    }

    @Test fun cellularSignalAndMissingMeasurementsRemainUnmeasured() {
        val scenarios = QualityScenarioLogic.evaluate(report(
            access = QualityAccess("Mobile data", null, null, null, true),
            dns = null, tls = null))
        assertEquals(QualityScenarioState.INFORMATIONAL, scenarios.scenario("signal").state)
        assertEquals(QualityScenarioState.UNKNOWN, scenarios.scenario("dns").state)
        assertEquals(QualityScenarioState.UNKNOWN, scenarios.scenario("tls").state)
    }

    @Test fun androidValidationFailureIsNotLabeledConfirmedCaptivePortal() {
        val scenarios = QualityScenarioLogic.evaluate(report(
            access = QualityAccess("Wi-Fi", -61, 2462, null, false)))
        val validation = scenarios.scenario("validation")
        assertEquals(QualityScenarioState.INFORMATIONAL, validation.state)
        assertTrue(validation.detail.contains("possible, not confirmed"))
    }
}
