package com.speedtester.app

/** One worker's size estimates and rejection ceiling for this upload phase. */
internal class UploadRequestSizing {
    private var preferred = TransferTuning.MIN_UPLOAD_BYTES
    private var ceiling = TransferTuning.MAX_UPLOAD_BYTES
    private var lastAccepted = 0

    fun requestBytes(remainingNanos: Long): Int =
        TransferTuning.uploadBytesForRemaining(preferred, remainingNanos).coerceAtMost(ceiling)

    /**
     * Returns true for accepted payload, false for recoverable HTTP 413.
     * Each handled rejection lowers the ceiling, so probes cannot loop indefinitely.
     */
    fun onResponse(code: Int, requestBytes: Int, elapsedNanos: Long): Boolean {
        if (code in 200..299) {
            lastAccepted = requestBytes
            preferred = TransferTuning.nextUploadBytes(requestBytes, elapsedNanos).coerceAtMost(ceiling)
            return true
        }
        if (code == 413 && requestBytes > TransferTuning.MIN_UPLOAD_BYTES) {
            val fallback = lastAccepted.takeIf { it in TransferTuning.MIN_UPLOAD_BYTES until requestBytes }
                ?: (requestBytes / 2).coerceAtLeast(TransferTuning.MIN_UPLOAD_BYTES)
            ceiling = minOf(ceiling, fallback)
            preferred = ceiling
            return false
        }
        error("Ookla upload endpoint returned HTTP $code")
    }
}
