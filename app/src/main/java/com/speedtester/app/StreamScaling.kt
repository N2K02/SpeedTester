package com.speedtester.app

/**
 * Ookla-style connection scaling. A fixed handful of TCP streams cannot fill a fast Wi-Fi line
 * (slow start, per-connection receive windows, per-socket server limits), so the phase starts
 * with [INITIAL] streams and adds [STEP] more every [CHECK_INTERVAL_MS] while throughput is still
 * climbing, up to [MAX]. The headline measurement window only starts once scaling has settled.
 */
internal object StreamScaling {
    const val INITIAL = 4
    const val STEP = 4
    const val MAX = 16
    const val CHECK_INTERVAL_MS = 1_000L
    /** Scaling is only attempted early in the phase so the measured window stays stable. */
    const val SCALING_DEADLINE_MS = 5_000L
    /** A one-second rate must improve by at least this fraction to justify more streams. */
    const val GROWTH_THRESHOLD = 0.10

    /**
     * Number of streams to add now. [previousMbps] is the rate of the previous check interval
     * (null for the first interval), [latestMbps] the rate of the interval just finished.
     */
    fun streamsToAdd(currentStreams: Int, elapsedMs: Long, previousMbps: Double?, latestMbps: Double): Int {
        if (currentStreams >= MAX || elapsedMs > SCALING_DEADLINE_MS) return 0
        if (!latestMbps.isFinite() || latestMbps <= 0.0) return 0
        val growing = previousMbps == null || previousMbps <= 0.0 ||
            latestMbps >= previousMbps * (1.0 + GROWTH_THRESHOLD)
        return if (growing) minOf(STEP, MAX - currentStreams) else 0
    }

    /**
     * When the headline (post ramp-up) window starts: after scaling stops, but never earlier than
     * [minWarmupMs] and never so late that fewer than [minMeasureMs] remain in the phase.
     */
    fun warmupReached(elapsedMs: Long, scalingSettled: Boolean, minWarmupMs: Long, phaseMs: Long, minMeasureMs: Long): Boolean {
        if (elapsedMs < minWarmupMs) return false
        return scalingSettled || elapsedMs >= phaseMs - minMeasureMs
    }
}
