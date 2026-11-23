package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test

class HistoryViewLogicTest {
    private fun test(server: String, type: String, date: String, speed: Double) =
        TestResult(date, speed, 10.0, 12L, 1.0, server, networkType = type)

    @Test fun filterByNetworkAndServerCaseInsensitively() {
        val entries = listOf(
            test("my.t Port Louis", "Wi-Fi", "2026-11-21 11:00", 120.0),
            test("Emtel Rose Hill", "Mobile data", "2026-11-22 10:00", 200.0),
            test("my.t Curepipe", "Wi-Fi", "2026-11-23 10:00", 95.0)
        )
        assertEquals(2, HistoryViewLogic.visible(entries, HistoryFilter.WIFI,
            HistoryOrder.LATEST, "MY.T").size)
        assertEquals(1, HistoryViewLogic.visible(entries, HistoryFilter.MOBILE,
            HistoryOrder.LATEST, "emTel").size)
        assertTrue(HistoryViewLogic.visible(entries, HistoryFilter.WIFI,
            HistoryOrder.LATEST, "not a server").isEmpty())
    }

    @Test fun originalSortAndAllHistoryRemainAvailable() {
        val entries = listOf(
            test("A", "Wi-Fi", "2026-11-21 11:00", 120.0),
            test("B", "Mobile data", "2026-11-23 11:00", 200.0)
        )
        assertEquals("B", HistoryViewLogic.visible(entries,
            HistoryFilter.ALL, HistoryOrder.FASTEST).first().server)
        assertEquals(2, HistoryViewLogic.visible(entries,
            HistoryFilter.ALL, HistoryOrder.LATEST).size)
    }
}
