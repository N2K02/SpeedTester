package com.speedtester.app

import kotlin.math.ceil

/** Pure speedometer math so animation targets and scale can be regression-tested. */
internal object SpeedometerScale {
    const val START_DEGREES = 150f
    const val SWEEP_DEGREES = 240f

    // A non-linear dial makes typical 5–100 Mbps speeds readable, while still supporting 1 Gbps.
    val baseMarks: List<Float> = listOf(0f, 5f, 10f, 50f, 100f, 250f, 500f, 750f, 1000f)
    val dialFractions: List<Float> = listOf(0f, 0.08f, 0.17f, 0.32f, 0.47f, 0.62f, 0.75f, 0.88f, 1f)

    fun sanitize(speed: Float): Float = if (speed.isFinite()) speed.coerceAtLeast(0f) else 0f

    fun scaleFor(speed: Float, previous: Float = 1000f): Float {
        val valid = sanitize(speed)
        val current = if (previous.isFinite() && previous >= 1000f) previous else 1000f
        return if (valid <= current) current else
            maxOf(current, ceil(valid / 500f) * 500f)
    }

    fun angleFor(speed: Float, scale: Float = 1000f): Float {
        val max = if (scale.isFinite() && scale > 0f) scale else 1000f
        val normalized = sanitize(speed).coerceAtMost(max) / max * 1000f
        val high = baseMarks.indexOfFirst { it >= normalized }.let { if (it < 0) baseMarks.lastIndex else it }
        if (high == 0) return START_DEGREES
        val low = high - 1
        val percent = ((normalized - baseMarks[low]) / (baseMarks[high] - baseMarks[low])).coerceIn(0f, 1f)
        val fraction = dialFractions[low] + (dialFractions[high] - dialFractions[low]) * percent
        return START_DEGREES + fraction * SWEEP_DEGREES
    }

    fun marks(scale: Float): List<Float> = baseMarks.map { it * (scale / 1000f) }
}
