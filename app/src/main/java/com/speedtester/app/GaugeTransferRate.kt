package com.speedtester.app

/** Rolling rate for the live needle, based on bytes moved during this transfer phase. */
internal class GaugeTransferRate(startedAtNanos: Long, private val windowNanos: Long = 750_000_000L) {
    init { require(windowNanos > 0L) }
    private data class Reading(val nanos: Long, val bytes: Long)

    private val readings = ArrayDeque<Reading>().apply { addLast(Reading(startedAtNanos, 0L)) }

    fun sample(nowNanos: Long, totalBytes: Long): Float {
        val previous = readings.last()
        if (nowNanos - previous.nanos > 0L) {
            readings.addLast(Reading(nowNanos, totalBytes.coerceAtLeast(previous.bytes)))
        }
        val latest = readings.last()
        // Keep the closest baseline at or before the window boundary.
        while (readings.size > 2 && latest.nanos - readings[1].nanos >= windowNanos) {
            readings.removeFirst()
        }
        val baseline = readings.first()
        val elapsed = latest.nanos - baseline.nanos
        if (elapsed <= 0L) return 0f
        if (elapsed <= windowNanos) return ((latest.bytes - baseline.bytes) * 8_000.0 / elapsed).toFloat()

        val next = readings[1]
        val expiredFraction = (elapsed - windowNanos).toDouble() / (next.nanos - baseline.nanos)
        val expiredBytes = (next.bytes - baseline.bytes) * expiredFraction
        return (((latest.bytes - baseline.bytes) - expiredBytes) * 8_000.0 / windowNanos).toFloat()
    }
}
