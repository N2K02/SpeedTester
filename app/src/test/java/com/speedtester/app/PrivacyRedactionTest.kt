package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test

class PrivacyRedactionTest {
    @Test fun diagnosticsHideWifiMacAndIpAddresses() {
        val safe = AppLog.redact("bssid aa:bb:cc:dd:ee:ff ip 192.168.1.15")
        assertFalse(safe.contains("aa:bb:cc:dd:ee:ff", true))
        assertFalse(safe.contains("192.168.1.15"))
        assertTrue(safe.contains("[MAC]"))
        assertTrue(safe.contains("[IP]"))
    }

    @Test fun diagnosticsKeepNonSensitiveMeasurementMetrics() {
        val safe = AppLog.redact("Downloaded 15 MB in 2.5s with ping 32 ms")
        assertTrue(safe.contains("Downloaded"))
        assertTrue(safe.contains("ping 32 ms"))
    }
}
