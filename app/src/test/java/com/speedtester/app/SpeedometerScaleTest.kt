package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test

class SpeedometerScaleTest {
    @Test fun gaugeDampensBurstyUploadSamples() {
        val raw = listOf(0f, 8.39f, 0f, 8.39f, 0f, 8.39f)
        val smoothed = raw.runningFold(0f, SpeedometerScale::smoothSample).drop(1)
        val rawAngles = raw.map { SpeedometerScale.angleFor(it) }
        val smoothedAngles = smoothed.map { SpeedometerScale.angleFor(it) }

        assertTrue(smoothed[1] < raw[1])
        assertTrue(smoothed[2] > 0f)
        assertTrue(smoothedAngles.zipWithNext().all { (before, after) ->
            kotlin.math.abs(after - before) < rawAngles[1] - rawAngles[0]
        })
    }

    @Test fun repeatedSamplesApproachTheSteadyRateAndZero() {
        var speed = 0f
        repeat(8) { speed = SpeedometerScale.smoothSample(speed, 100f) }
        assertEquals(100f, speed, 0.5f)
        repeat(8) { speed = SpeedometerScale.smoothSample(speed, 0f) }
        assertEquals(0f, speed, 0.5f)
    }

    @Test fun badSamplesCannotPoisonTheGauge() {
        assertEquals(25f, SpeedometerScale.smoothSample(50f, Float.NaN), 0f)
        assertEquals(25f, SpeedometerScale.smoothSample(Float.POSITIVE_INFINITY, 50f), 0f)
        assertEquals(0f, SpeedometerScale.smoothSample(-1f, -1f), 0f)
        assertEquals(Float.MAX_VALUE, SpeedometerScale.smoothSample(Float.MAX_VALUE, Float.MAX_VALUE), 0f)
    }

    @Test fun needleAtZeroAndFullScale() {
        assertEquals(150f, SpeedometerScale.angleFor(0f), 0.001f)
        assertEquals(390f, SpeedometerScale.angleFor(1000f), 0.001f)
    }

    @Test fun needleMovesMonotonicallyAndSmoothlyBetweenMarks() {
        val values = listOf(0f, 1f, 5f, 7.5f, 10f, 25f, 50f, 100f, 250f, 500f, 750f, 1000f)
        val angles = values.map { SpeedometerScale.angleFor(it) }
        assertTrue(angles.zipWithNext().all { (left, right) -> right > left })
        assertEquals((SpeedometerScale.angleFor(5f) + SpeedometerScale.angleFor(10f)) / 2f,
            SpeedometerScale.angleFor(7.5f), 0.001f)
    }

    @Test fun badSamplesAreSafeAndNeedleNeverExceedsTheDial() {
        assertEquals(150f, SpeedometerScale.angleFor(Float.NaN), 0f)
        assertEquals(150f, SpeedometerScale.angleFor(-1f), 0f)
        assertEquals(150f, SpeedometerScale.angleFor(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(150f, SpeedometerScale.angleFor(Float.POSITIVE_INFINITY), 0f)
        assertEquals(390f, SpeedometerScale.angleFor(99999f), 0f)
    }

    @Test fun scaleGrowsForFastConnectionsWithoutShrinkingDuringATest() {
        assertEquals(1000f, SpeedometerScale.scaleFor(500f), 0f)
        assertEquals(1500f, SpeedometerScale.scaleFor(1130f), 0f)
        assertEquals(1500f, SpeedometerScale.scaleFor(300f, 1500f), 0f)
        assertEquals(390f, SpeedometerScale.angleFor(1500f, 1500f), 0f)
        assertEquals(1500f, SpeedometerScale.marks(1500f).last(), 0f)
    }

    @Test fun phaseSelectionUsesItsOwnSpeed() {
        assertTrue(SpeedometerScale.angleFor(100f) > SpeedometerScale.angleFor(20f))
        assertEquals(150f, SpeedometerScale.angleFor(0f, 2000f), 0f)
    }
}
