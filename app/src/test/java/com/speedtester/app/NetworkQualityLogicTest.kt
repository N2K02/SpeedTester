package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test

class NetworkQualityLogicTest {
    @Test fun unavailableDoesNotInventScore() {
        val r = NetworkQualityLogic.evaluate(listOf(null,null))
        assertNull(r.grade)
        assertEquals("Unavailable", NetworkQualityLogic.label(r.grade))
    }
    @Test fun stableLowLatencyScoresHigh() {
        val r = NetworkQualityLogic.evaluate(listOf(10L,11L,10L,12L))
        assertTrue(r.grade!! >= 90)
        assertEquals(4,r.successes)
    }
    @Test fun failedConnectionsPenalized() {
        val good = NetworkQualityLogic.evaluate(listOf(20L,20L,20L))
        val bad = NetworkQualityLogic.evaluate(listOf(20L,null,20L))
        assertTrue(bad.grade!! < good.grade!!)
    }
    @Test fun scoreAlwaysBounded() {
        assertEquals(0,NetworkQualityLogic.evaluate(listOf(99999L)).grade)
    }
}
