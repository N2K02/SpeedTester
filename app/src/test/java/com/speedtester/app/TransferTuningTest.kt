package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test

class TransferTuningTest {
    @Test fun increasesAndDecreasesBoundedPayload() {
        assertEquals(512 * 1024, TransferTuning.nextUploadBytes(256 * 1024, 100_000_000L))
        assertEquals(256 * 1024, TransferTuning.nextUploadBytes(512 * 1024, 900_000_000L))
        assertEquals(TransferTuning.MAX_UPLOAD_BYTES, TransferTuning.nextUploadBytes(TransferTuning.MAX_UPLOAD_BYTES, 1L))
        assertEquals(TransferTuning.MIN_UPLOAD_BYTES, TransferTuning.nextUploadBytes(TransferTuning.MIN_UPLOAD_BYTES, Long.MAX_VALUE))
        assertEquals(512 * 1024, TransferTuning.nextUploadBytes(512 * 1024, 0L))
    }
    @Test fun shrinksRequestsNearDeadline() {
        assertEquals(TransferTuning.MIN_UPLOAD_BYTES, TransferTuning.uploadBytesForRemaining(TransferTuning.MAX_UPLOAD_BYTES, 500_000_000L))
        assertEquals(TransferTuning.MAX_UPLOAD_BYTES, TransferTuning.uploadBytesForRemaining(TransferTuning.MAX_UPLOAD_BYTES, 2_000_000_000L))
    }
    @Test fun rejectsHtmlAndUnknownDownloadTypes() {
        assertTrue(TransferTuning.validDownloadType("image/jpeg"))
        assertTrue(TransferTuning.validDownloadType("application/octet-stream"))
        assertFalse(TransferTuning.validDownloadType("text/html"))
        assertFalse(TransferTuning.validDownloadType(null))
    }
}
