package com.speedtester.app

/** Pure measurement decisions shared by real tests and JVM regression tests. */
internal object TransferAccounting {
    // Acknowledged upload bytes are an integrity signal, not a throughput clock.
    // HTTP response times include server processing and can arrive well after socket writes.
    fun countableBytes(sent: Long, acknowledged: Long, upload: Boolean): Long =
        (if (upload) acknowledged else sent).coerceAtLeast(0L)

    fun measuredBytes(moved: Long): Long = moved.coerceAtLeast(0L)

    fun uploadHasServerConfirmation(acknowledged: Long): Boolean = acknowledged > 0L

    fun loadedPing(down: Long, up: Long): Long =
        listOf(down, up).filter { it >= 0L }.maxOrNull() ?: -1L

    fun loadedJitter(down: Double, up: Double): Double =
        maxOf(if (down.isFinite()) down.coerceAtLeast(0.0) else 0.0,
            if (up.isFinite()) up.coerceAtLeast(0.0) else 0.0)
}
