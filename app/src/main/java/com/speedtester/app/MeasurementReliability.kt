package com.speedtester.app

/** Fail closed when all throughput workers have exhausted retries before the test deadline. */
internal object MeasurementReliability {
    fun allWorkersFailed(completedWorkers: Int, totalWorkers: Int, phaseStopped: Boolean): Boolean =
        totalWorkers > 0 && completedWorkers >= totalWorkers && !phaseStopped

    /** Recover dead workers during ramp-up only, with a strict lifetime launch budget. */
    fun replacementCount(active: Int, target: Int, launched: Int, maxLaunches: Int): Int =
        (target - active).coerceAtLeast(0).coerceAtMost((maxLaunches - launched).coerceAtLeast(0))

    /** A single lucky connection is not sufficient evidence for server ranking. */
    fun discoveryPingMs(probes: List<Long>): Long? {
        val sorted = probes.filter { it >= 0 }.sorted()
        if (sorted.size < 2) return null
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else sorted[mid - 1] + (sorted[mid] - sorted[mid - 1]) / 2
    }

    /** A blocked TCP probe must not prevent a working HTTPS throughput test. */
    fun idlePingMs(successfulProbes: List<Long>): Long =
        successfulProbes.filter { it >= 0L }.takeIf { it.isNotEmpty() }?.average()?.toLong() ?: -1L

    fun idleJitterMs(successfulProbes: List<Long>): Double {
        val valid = successfulProbes.filter { it >= 0L }
        return if (valid.size < 2) 0.0
        else valid.zipWithNext { a, b -> kotlin.math.abs(a - b).toDouble() }.average()
    }
}
