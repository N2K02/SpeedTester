package com.speedtester.app

/**
 * Cumulative byte samples with a fixed warm-up boundary and a one-second peak window.
 * Uses monotonic nanosecond timestamps, so sample scheduling jitter does not shift warm-up.
 */
internal class ThroughputSamples(
    private val startedNanos: Long,
    private val warmupNanos: Long = 1_500_000_000L,
    private val peakWindowNanos: Long = 1_000_000_000L,
) {
    private data class Point(val time: Long, val bytes: Long)
    private val points = ArrayList<Point>().apply { add(Point(startedNanos, 0L)) }
    private var peakMbps = 0.0

    fun record(timeNanos: Long, bytes: Long) {
        val last = points.last()
        if (timeNanos <= last.time) return
        points.add(Point(timeNanos, bytes.coerceAtLeast(last.bytes)))
        val elapsed = timeNanos - startedNanos
        if (elapsed >= peakWindowNanos) {
            val baseline = bytesAt(timeNanos - peakWindowNanos)
            val rate = mbps(points.last().bytes - baseline, peakWindowNanos)
            peakMbps = maxOf(peakMbps, rate)
        }
    }

    fun averageMbps(): Double {
        val end = points.last()
        return mbps(end.bytes, end.time - startedNanos)
    }

    fun steadyMbps(): Double {
        val end = points.last()
        val elapsed = end.time - startedNanos
        if (elapsed <= warmupNanos) return averageMbps()
        val boundary = startedNanos + warmupNanos
        return mbps(end.bytes - bytesAt(boundary), end.time - boundary)
    }

    fun peakMbps(): Double = maxOf(peakMbps, steadyMbps().takeIf { it.isFinite() } ?: 0.0)

    private fun bytesAt(time: Long): Double {
        if (time <= startedNanos) return 0.0
        val end = points.last()
        if (time >= end.time) return end.bytes.toDouble()
        val high = points.indexOfFirst { it.time >= time }
        val before = points[high - 1]
        val after = points[high]
        val portion = (time - before.time).toDouble() / (after.time - before.time)
        return before.bytes + (after.bytes - before.bytes) * portion
    }

    private fun mbps(bytes: Double, nanos: Long): Double =
        if (nanos <= 0L) 0.0 else (bytes.coerceAtLeast(0.0) * 8_000.0 / nanos).coerceAtLeast(0.0)
    private fun mbps(bytes: Long, nanos: Long): Double = mbps(bytes.toDouble(), nanos)
}

/** Keep upload requests small on slow links, but amortize HTTP overhead on fast ones. */
internal object UploadRequestSizing {
    const val MIN_BYTES = 256 * 1024
    const val MAX_BYTES = 2 * 1024 * 1024

    fun choose(acceptedBytes: Long, elapsedNanos: Long): Int {
        if (elapsedNanos <= 0L) return MIN_BYTES
        val confirmedMbps = acceptedBytes.coerceAtLeast(0L) * 8_000.0 / elapsedNanos
        return when {
            confirmedMbps >= 300.0 -> MAX_BYTES
            confirmedMbps >= 60.0 -> 1024 * 1024
            confirmedMbps >= 15.0 -> 512 * 1024
            else -> MIN_BYTES
        }
    }
}
