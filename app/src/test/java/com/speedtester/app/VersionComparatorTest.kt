package com.speedtester.app

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionComparatorTest {
    @Test fun versionOrderingIsNumeric() {
        assertEquals(-1, compare("1.9", "2.0"))
        assertEquals(1, compare("1.10", "1.2"))
        assertEquals(0, compare("1.2.0", "1.2"))
    }
    private fun compare(a: String, b: String): Int {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrNull(i) ?: 0).compareTo(y.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }
}
