package com.speedtester.app

import org.junit.Assert.*
import org.junit.Test

class SpeedometerScaleTest {
    @Test fun needleAtZeroAndFullScale() {
        assertEquals(150f, SpeedometerScale.angleFor(0f), 0.001f)
        assertEquals(390f, SpeedometerScale.angleFor(10_000f), 0.001f)
    }

    @Test fun needleMovesMonotonicallyAndSmoothlyBetweenMarks() {
        val values = listOf(0f, 1f, 5f, 7.5f, 10f, 25f, 50f, 100f, 250f, 500f, 750f, 1000f, 2500f, 10_000f)
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

    @Test fun crossingOneGigabitDoesNotMoveTheNeedleBackward() {
        val below = SpeedometerScale.angleFor(999f)
        val above = SpeedometerScale.angleFor(1001f)
        assertTrue(above > below)
        assertTrue(above - below < 1f)
        assertEquals(SpeedometerScale.marks.size, SpeedometerScale.dialFractions.size)
    }

    @Test fun phaseSelectionUsesItsOwnSpeed() {
        assertTrue(SpeedometerScale.angleFor(100f) > SpeedometerScale.angleFor(20f))
        assertEquals(150f, SpeedometerScale.angleFor(0f), 0f)
    }

    @Test fun majorTicksLabelsAndNeedleAgreeOnEveryMark() {
        val ticks = SpeedometerScale.ticks
        val major = ticks.filter { it.major }
        assertEquals(SpeedometerScale.marks.size, major.size)
        assertTrue(ticks.zipWithNext().all { (left, right) -> left.degrees < right.degrees })
        SpeedometerScale.marks.forEachIndexed { index, speed ->
            assertEquals(major[index].degrees, SpeedometerScale.markAngle(index), 0.001f)
            assertEquals(major[index].degrees, SpeedometerScale.angleFor(speed), 0.001f)
        }
    }

    @Test fun needleAndReadoutReturnToZeroWhenTheTestFinishes() {
        val display = SpeedometerDisplay.select(false, true, 36.1f, 37f)
        assertEquals(SpeedometerDisplay(0f, 0f, false), display)
        assertEquals(SpeedometerScale.START_DEGREES, SpeedometerScale.angleFor(display.needleMbps), 0f)
    }

    @Test fun liveDialPreservesNeedleAndReadoutIndependently() {
        assertEquals(SpeedometerDisplay(0f, 0f, false), SpeedometerDisplay.select(true, false, 0f, 0f))
        assertEquals(SpeedometerDisplay(110f, 112f, false), SpeedometerDisplay.select(true, false, 110f, 112f))
        assertEquals(SpeedometerDisplay(34f, 37f, true), SpeedometerDisplay.select(true, true, 34f, 37f))
    }
}
