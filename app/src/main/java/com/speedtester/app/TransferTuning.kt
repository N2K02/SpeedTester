package com.speedtester.app

/** Bounded per-request upload adaptation, based on successful server round trips. */
internal object TransferTuning {
    const val MIN_UPLOAD_BYTES = 256 * 1024
    const val MAX_UPLOAD_BYTES = 2 * 1024 * 1024
    private const val TARGET_NANOS = 300_000_000L

    fun nextUploadBytes(previous: Int, elapsedNanos: Long): Int {
        val current = previous.coerceIn(MIN_UPLOAD_BYTES, MAX_UPLOAD_BYTES)
        if (elapsedNanos <= 0) return current
        val estimate = current.toDouble() * TARGET_NANOS / elapsedNanos
        return estimate.coerceIn(current / 2.0, current * 2.0)
            .coerceIn(MIN_UPLOAD_BYTES.toDouble(), MAX_UPLOAD_BYTES.toDouble()).toInt()
    }

    fun uploadBytesForRemaining(preferred: Int, remainingNanos: Long): Int =
        if (remainingNanos < 1_000_000_000L) MIN_UPLOAD_BYTES
        else preferred.coerceIn(MIN_UPLOAD_BYTES, MAX_UPLOAD_BYTES)

    fun validDownloadType(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
        return type == "image/jpeg" || type == "image/jpg" || type == "application/octet-stream"
    }
}
