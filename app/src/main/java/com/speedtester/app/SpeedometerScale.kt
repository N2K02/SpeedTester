package com.speedtester.app

/** Pure speedometer math for animation targets and scale. */
internal object SpeedometerScale {
    const val START_DEGREES = 150f
    const val SWEEP_DEGREES = 240f

    // A fixed non-linear scale keeps low speeds readable and fast samples monotonic.
    val marks: List<Float> = listOf(0f, 5f, 10f, 50f, 100f, 250f, 500f, 1000f, 2500f, 10000f)
    val dialFractions: List<Float> = listOf(0f, 0.08f, 0.17f, 0.29f, 0.41f, 0.54f, 0.66f, 0.78f, 0.89f, 1f)

    data class Tick(val degrees: Float, val major: Boolean)

    fun markAngle(index: Int): Float = START_DEGREES + dialFractions[index] * SWEEP_DEGREES

    // Put major ticks at the labeled values, not at evenly spaced dial angles.
    val ticks: List<Tick> = buildList {
        for (index in marks.indices) {
            val from = markAngle(index)
            add(Tick(from, true))
            if (index < marks.lastIndex) {
                val step = (markAngle(index + 1) - from) / 5f
                for (minor in 1..4) add(Tick(from + minor * step, false))
            }
        }
    }

    fun sanitize(speed: Float): Float = if (speed.isFinite()) speed.coerceAtLeast(0f) else 0f

    fun angleFor(speed: Float): Float {
        val normalized = sanitize(speed).coerceAtMost(marks.last())
        val high = marks.indexOfFirst { it >= normalized }
        if (high == 0) return START_DEGREES
        val low = high - 1
        val percent = (normalized - marks[low]) / (marks[high] - marks[low])
        val fraction = dialFractions[low] + (dialFractions[high] - dialFractions[low]) * percent
        return START_DEGREES + fraction * SWEEP_DEGREES
    }
}


/** The idle dial resets to zero; the separate result cards keep saved results. */
internal data class SpeedometerDisplay(
    val readoutMbps: Float,
    val needleMbps: Float,
    val isUpload: Boolean
) {
    companion object {
        fun select(
            testing: Boolean,
            liveIsUpload: Boolean,
            liveReadoutMbps: Float,
            liveNeedleMbps: Float
        ): SpeedometerDisplay =
            if (testing) SpeedometerDisplay(liveReadoutMbps, liveNeedleMbps, liveIsUpload)
            else SpeedometerDisplay(0f, 0f, false)
    }
}
