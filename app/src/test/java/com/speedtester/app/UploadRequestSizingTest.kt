package com.speedtester.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadRequestSizingTest {
    private val kib = 1024
    private val remaining = 5_000_000_000L
    private val quick = 100_000_000L

    @Test fun cappedEndpointRecoversWithoutCountingRejectedPayloadOrProbingItAgain() {
        val sizing = UploadRequestSizing()
        val requests = mutableListOf<Int>()
        var acceptedBytes = 0
        repeat(7) {
            val bytes = sizing.requestBytes(remaining)
            requests += bytes / kib
            val status = if (bytes <= 1024 * kib) 200 else 413
            if (sizing.onResponse(status, bytes, quick)) acceptedBytes += bytes
        }
        assertEquals(listOf(256, 512, 1024, 2048, 1024, 1024, 1024), requests)
        assertEquals((256 + 512 + 1024 * 4) * kib, acceptedBytes)
    }

    @Test fun uncappedEndpointStillScalesAndTapersNearDeadline() {
        val sizing = UploadRequestSizing()
        val requests = List(5) {
            val bytes = sizing.requestBytes(remaining)
            assertTrue(sizing.onResponse(204, bytes, quick))
            bytes / kib
        }
        assertEquals(listOf(256, 512, 1024, 2048, 2048), requests)
        assertEquals(256 * kib, sizing.requestBytes(500_000_000L))
    }

    @Test fun aFallingServerLimitReducesTheCeilingAgainAndMinimumRejectionIsAnError() {
        val sizing = UploadRequestSizing()
        for (bytes in listOf(256, 512, 1024, 2048)) {
            assertEquals(bytes * kib, sizing.requestBytes(remaining))
            assertTrue(sizing.onResponse(200, bytes * kib, quick))
        }
        for (bytes in listOf(2048, 1024, 512)) {
            assertEquals(bytes * kib, sizing.requestBytes(remaining))
            assertFalse(sizing.onResponse(413, bytes * kib, quick))
        }
        assertEquals(256 * kib, sizing.requestBytes(remaining))
        val failure = assertThrows(IllegalStateException::class.java) {
            sizing.onResponse(413, 256 * kib, quick)
        }
        assertTrue(failure.message.orEmpty().contains("413"))
    }

    @Test fun otherHttpErrorsDoNotChangeSizing() {
        val sizing = UploadRequestSizing()
        assertTrue(sizing.onResponse(200, 256 * kib, quick))
        assertThrows(IllegalStateException::class.java) { sizing.onResponse(503, 512 * kib, quick) }
        assertEquals(512 * kib, sizing.requestBytes(remaining))
    }
}
