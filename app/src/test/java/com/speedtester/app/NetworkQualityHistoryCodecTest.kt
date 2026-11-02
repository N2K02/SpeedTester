package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test

class NetworkQualityHistoryCodecTest {
    @Test fun oldCompactRecordsStillReadWithNoFabricatedScenarios() {
        val old = """[{"time":100,"score":72,"transport":"Wi-Fi","tcp":25}]"""
        val items = QualityHistoryCodec.decode(old)
        assertEquals(1, items.size)
        assertEquals(72, items.first().score)
        assertEquals(25L, items.first().tcpMs)
        assertNull(items.first().dnsMs)
        assertNull(items.first().tlsMs)
        assertTrue(items.first().scenarios.isEmpty())
    }

    @Test fun scenariosAndAllMeasuredFieldsRoundtrip() {
        val item = QualityHistoryItem(
            timestamp = 200,
            score = 83,
            transport = "Wi-Fi",
            tcpMs = 19,
            dnsMs = 34,
            tlsMs = 120,
            jitterMs = 2.7,
            httpsResponses = 6,
            httpsAttempts = 7,
            profile = "DETAILED",
            durationMs = 3400,
            cpuMs = 40,
            trafficBytes = 2560,
            scenarios = listOf(QualityScenarioResult("tls", "TLS handshake",
                QualityScenarioState.GOOD, "120 ms"))
        )
        val restored = QualityHistoryCodec.decode(QualityHistoryCodec.encode(listOf(item)))
        assertEquals(item, restored.single())
    }

    @Test fun malformedInputDoesNotCrashAndStoredHistoryIsBounded() {
        assertTrue(QualityHistoryCodec.decode("{broken").isEmpty())
        val items = (1..70).map {
            QualityHistoryItem(timestamp = it.toLong(), score = it, transport = "Wi-Fi", tcpMs = it.toLong())
        }
        val restored = QualityHistoryCodec.decode(QualityHistoryCodec.encode(items))
        assertEquals(QualityHistoryCodec.LIMIT, restored.size)
        assertEquals(70L, restored.first().timestamp)
        assertEquals(41L, restored.last().timestamp)
    }

    @Test fun invalidScenarioStateDoesNotCrash() {
        val malformed = """[{"time":111,"transport":"Wi-Fi","scenarios":[{"id":"http","title":"HTTP","state":"INVALID","detail":"No result"}]}]"""
        val item = QualityHistoryCodec.decode(malformed).single()
        assertEquals(QualityScenarioState.UNKNOWN, item.scenarios.single().state)
    }
}
