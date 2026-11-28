package com.speedtester.app

/** Explore more parallel TCP connections before declaring saturation. */
internal object StreamScaling {
    const val INITIAL = 4
    const val STEP = 4
    const val MAX = 16
    const val CHECK_INTERVAL_MS = 1_000L
    const val SCALING_DEADLINE_MS = 6_000L
    const val GROWTH_THRESHOLD = 0.10
    const val PLATEAU_INTERVALS = 2

    fun streamsToAdd(currentStreams: Int, elapsedMs: Long, previousMbps: Double?, latestMbps: Double,
                     upload: Boolean = false): Int {
        if (currentStreams >= MAX || elapsedMs >= SCALING_DEADLINE_MS) return 0
        if (!latestMbps.isFinite() || latestMbps <= 0.0) return 0
        // Probe at least 12 download streams (8 upload) even if a single interval is flat.
        val minimum = if (upload) 8 else 12
        val growing = previousMbps == null || previousMbps <= 0.0 ||
            latestMbps >= previousMbps * (1.0 + GROWTH_THRESHOLD)
        return if (currentStreams < minimum || growing) minOf(STEP, MAX - currentStreams) else 0
    }

    fun settled(currentStreams: Int, elapsedMs: Long, latestMbps: Double, added: Int,
                plateauIntervals: Int): Boolean {
        if (added > 0) return false
        if (elapsedMs >= SCALING_DEADLINE_MS) return true
        // Allow newly launched maximum streams to warm up for two observations as well.
        return latestMbps.isFinite() && latestMbps > 0.0 && plateauIntervals >= PLATEAU_INTERVALS
    }

    fun warmupReached(elapsedMs: Long, scalingSettled: Boolean, minWarmupMs: Long,
                      phaseMs: Long, minMeasureMs: Long): Boolean {
        if (elapsedMs < minWarmupMs) return false
        return scalingSettled || elapsedMs >= phaseMs - minMeasureMs
    }
}
