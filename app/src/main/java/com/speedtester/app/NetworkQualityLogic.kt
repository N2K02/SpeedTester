package com.speedtester.app

import kotlin.math.abs
import kotlin.math.roundToInt

/** TCP connect timings are not ICMP ping, UDP loss, or application performance. */
data class QualitySample(
    val latencyMs: Long?,
    val jitterMs: Double?,
    val successes: Int,
    val attempts: Int,
    val grade: Int?,
    val note: String
)

object NetworkQualityLogic {
    fun evaluate(samples: List<Long?>): QualitySample {
        val valid = samples.filterNotNull()
        if (valid.isEmpty()) return QualitySample(null, null, 0, samples.size, null,
            "TCP target unavailable; this does not prove the internet is down.")
        val sorted = valid.sorted()
        val median = sorted[sorted.size / 2]
        // Never bridge a failed measurement when computing consecutive timing variation.
        val variation = samples.zipWithNext().mapNotNull { (a, b) ->
            if (a != null && b != null) abs(a - b).toDouble() else null
        }
        val jitter = if (variation.isEmpty()) null else variation.average()
        val failures = samples.size - valid.size
        val score = (100.0 - median * .35 - (jitter ?: 0.0) * .7 - failures * 12)
            .roundToInt().coerceIn(0, 100)
        return QualitySample(median, jitter, valid.size, samples.size, score,
            if (failures > 0) "$failures TCP connections failed; this does not measure packet loss"
            else "TCP connection responsiveness")
    }

    fun label(score: Int?): String = when {
        score == null -> "Unavailable"
        score >= 90 -> "Excellent"
        score >= 75 -> "Very good"
        score >= 60 -> "Good"
        score >= 40 -> "Fair"
        else -> "Poor"
    }
}
