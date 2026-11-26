package com.speedtester.app

/**
 * Turns payload byte counts into the headline speed reported by mainstream testers.
 *
 * - Line rate: payload bytes exclude HTTP/TCP/IP/Ethernet headers. LibreSpeed compensates with
 *   1048576/925000 (only ~925000 of every MiB on the wire is payload); Ookla-style results are
 *   comparable to this line rate rather than raw payload.
 * - Trimmed rate: like Ookla/Cloudflare, transient Wi-Fi dips should not drag the result down.
 *   Rates over sliding 1 s windows are sorted, the slowest 30 % and fastest 10 % dropped and the
 *   rest averaged.
 */
internal object ThroughputReport {
    const val OVERHEAD_FACTOR = 1_048_576.0 / 925_000.0
    const val WINDOW_NANOS = 1_000_000_000L
    const val DROP_SLOWEST = 0.30
    const val DROP_FASTEST = 0.10
    const val MIN_WINDOWS = 5

    data class Point(val nanos: Long, val bytes: Long)

    fun lineRate(payloadMbps: Double): Double =
        if (!payloadMbps.isFinite() || payloadMbps <= 0.0) 0.0 else payloadMbps * OVERHEAD_FACTOR

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
