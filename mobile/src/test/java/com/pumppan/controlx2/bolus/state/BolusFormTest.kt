package com.pumppan.controlx2.bolus.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BolusFormTest {

    @Test
    fun splitByPercent_halfRoundsUp() {
        val split = BolusForm.splitByPercent(totalMilliunits = 401, percent = 50)
        assertEquals(201, split.nowMilliunits)
        assertEquals(200, split.laterMilliunits)
    }

    @Test
    fun splitByPercent_remainderGoesToLater() {
        val split = BolusForm.splitByPercent(totalMilliunits = 55, percent = 50)
        assertEquals(28, split.nowMilliunits)
        assertEquals(27, split.laterMilliunits)
    }

    @Test
    fun splitByPercent_70_30() {
        val split = BolusForm.splitByPercent(totalMilliunits = 401, percent = 70)
        assertEquals(281, split.nowMilliunits)
        assertEquals(120, split.laterMilliunits)
    }

    @Test
    fun splitByPercent_edges() {
        assertEquals(BolusSplit(0, 55), BolusForm.splitByPercent(55, 0))
        assertEquals(BolusSplit(55, 0), BolusForm.splitByPercent(55, 100))
    }

    @Test
    fun splitByPercent_alwaysKeepsTotal() {
        for (total in listOf(50, 99, 100, 401, 25_000)) {
            for (percent in 0..100) {
                val split = BolusForm.splitByPercent(total, percent)
                assertEquals(total, split.nowMilliunits + split.laterMilliunits)
            }
        }
    }

    @Test
    fun validate_acceptsMinimumAndRejectsBelow() {
        val minimum = BolusDraft(nowMilliunits = 50)
        assertTrue(BolusForm.isValid(minimum, ciqEnabled = false))

        val below = BolusDraft(nowMilliunits = 49)
        assertEquals(
            listOf(BolusValidationError.AMOUNT_BELOW_MINIMUM),
            BolusForm.validate(below, ciqEnabled = false),
        )
    }

    @Test
    fun validate_acceptsMaximumAndRejectsAbove() {
        val maximum = BolusDraft(nowMilliunits = 25_000)
        assertTrue(BolusForm.isValid(maximum, ciqEnabled = false))

        val above = BolusDraft(nowMilliunits = 25_001)
        assertEquals(
            listOf(BolusValidationError.AMOUNT_ABOVE_MAXIMUM),
            BolusForm.validate(above, ciqEnabled = false),
        )
    }

    @Test
    fun validate_negativeAmount() {
        val draft = BolusDraft(nowMilliunits = -1)
        assertEquals(
            listOf(BolusValidationError.NEGATIVE_AMOUNT),
            BolusForm.validate(draft, ciqEnabled = false),
        )
    }

    @Test
    fun validate_squareNowBelowMinimum() {
        val draft = BolusDraft(
            nowMilliunits = 49,
            laterMilliunits = 100,
            extendedSeconds = 3_600,
            shape = BolusShape.SQUARE,
        )
        assertTrue(BolusForm.validate(draft, ciqEnabled = false).contains(BolusValidationError.NOW_BELOW_MINIMUM))
    }

    @Test
    fun validate_squareLaterRequired() {
        val draft = BolusDraft(
            nowMilliunits = 50,
            laterMilliunits = 0,
            extendedSeconds = 3_600,
            shape = BolusShape.SQUARE,
        )
        assertTrue(BolusForm.validate(draft, ciqEnabled = false).contains(BolusValidationError.LATER_REQUIRED))
    }

    @Test
    fun validate_durationGating_ciq() {
        val draft = BolusDraft(
            nowMilliunits = 50,
            laterMilliunits = 100,
            extendedSeconds = 7_201,
            shape = BolusShape.DUAL,
        )
        assertTrue(BolusForm.validate(draft, ciqEnabled = true).contains(BolusValidationError.DURATION_OUT_OF_RANGE))
        assertFalse(BolusForm.validate(draft, ciqEnabled = false).contains(BolusValidationError.DURATION_OUT_OF_RANGE))
    }

    @Test
    fun validate_durationGating_nonCiqUpperBound() {
        val draft = BolusDraft(
            nowMilliunits = 50,
            laterMilliunits = 100,
            extendedSeconds = 28_801,
            shape = BolusShape.SQUARE,
        )
        assertTrue(BolusForm.validate(draft, ciqEnabled = false).contains(BolusValidationError.DURATION_OUT_OF_RANGE))
    }

    @Test
    fun validate_normalShapeInconsistent() {
        assertTrue(
            BolusForm.validate(BolusDraft(nowMilliunits = 100, laterMilliunits = 50), ciqEnabled = false)
                .contains(BolusValidationError.SHAPE_INCONSISTENT),
        )
        assertTrue(
            BolusForm.validate(BolusDraft(nowMilliunits = 100, extendedSeconds = 60), ciqEnabled = false)
                .contains(BolusValidationError.SHAPE_INCONSISTENT),
        )
    }

    @Test
    fun maxExtendedSeconds_byCiqState() {
        assertEquals(7_200, BolusForm.maxExtendedSeconds(ciqEnabled = true))
        assertEquals(28_800, BolusForm.maxExtendedSeconds(ciqEnabled = false))
        assertEquals(7_200, BolusForm.maxExtendedSeconds(ciqEnabled = null))
    }
}
