package com.speedtester.app

/** Fail closed when all throughput workers have exhausted retries before the test deadline. */
internal object MeasurementReliability {
    fun allWorkersFailed(completedWorkers: Int, totalWorkers: Int, phaseStopped: Boolean): Boolean =
        totalWorkers > 0 && completedWorkers >= totalWorkers && !phaseStopped

    /** A blocked TCP probe must not prevent a working HTTPS throughput test. */
    fun idlePingMs(successfulProbes: List<Long>): Long =
        successfulProbes.filter { it >= 0L }.takeIf { it.isNotEmpty() }?.average()?.toLong() ?: -1L

    fun idleJitterMs(successfulProbes: List<Long>): Double {
        val valid = successfulProbes.filter { it >= 0L }
        return if (valid.size < 2) 0.0
        else valid.zipWithNext { a, b -> kotlin.math.abs(a - b).toDouble() }.average()
    }
}
