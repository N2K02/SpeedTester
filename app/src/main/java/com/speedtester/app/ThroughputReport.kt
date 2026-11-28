package com.speedtester.app

/** Decimal Mbps from observed payload bytes. No assumed network-overhead multiplier.
 * A trimmed rate is diagnostic only; the headline uses the full post-warmup window.
 */
internal object ThroughputReport {
    const val WINDOW_NANOS = 1_000_000_000L
    const val DROP_SLOWEST = 0.30
    const val DROP_FASTEST = 0.10
    const val MIN_WINDOWS = 5

    data class Point(val nanos: Long, val bytes: Long)

    fun payloadRate(payloadMbps: Double): Double =
        if (!payloadMbps.isFinite() || payloadMbps <= 0.0) 0.0 else payloadMbps

    fun measuredMbps(bytes: Long, nanos: Long): Double =
        if (bytes <= 0 || nanos <= 0) 0.0 else bytes * 8_000.0 / nanos

    /** Compare four independent one-second buckets, not overlapping samples. */
    fun stable(points: List<Point>): Boolean {
        if (points.size < 2) return false
        val rates = mutableListOf<Double>()
        var end = points.lastIndex
        repeat(4) {
            var start = end - 1
            while (start >= 0 && points[end].nanos - points[start].nanos < WINDOW_NANOS) start--
            if (start < 0) return false
            rates.add(measuredMbps(points[end].bytes - points[start].bytes, points[end].nanos - points[start].nanos))
            end = start
        }
        val mean = rates.average()
        return mean > 0.0 && rates.all { kotlin.math.abs(it - mean) <= mean * 0.20 }
    }

    /** Rates (payload Mbps) of every sliding window of at least [WINDOW_NANOS]. */
    fun windowRates(points: List<Point>, windowNanos: Long = WINDOW_NANOS): List<Double> {
        val rates = mutableListOf<Double>()
        var j = 0
        for (i in points.indices) {
            if (j <= i) j = i + 1
            while (j < points.size && points[j].nanos - points[i].nanos < windowNanos) j++
            if (j >= points.size) break
            val nanos = points[j].nanos - points[i].nanos
            val bytes = (points[j].bytes - points[i].bytes).coerceAtLeast(0L)
            if (nanos > 0L) rates.add(bytes * 8_000.0 / nanos)
        }
        return rates
    }

    /** Trimmed mean of sliding-window rates, or null when too few windows exist to trim fairly. */
    fun trimmedMbps(points: List<Point>, windowNanos: Long = WINDOW_NANOS): Double? {
        val rates = windowRates(points, windowNanos).filter { it.isFinite() }.sorted()
        if (rates.size < MIN_WINDOWS) return null
        val low = (rates.size * DROP_SLOWEST).toInt()
        val high = (rates.size * DROP_FASTEST).toInt()
        val kept = rates.subList(low, rates.size - high)
        return if (kept.isEmpty()) null else kept.average()
    }
}
