package com.pumppan.controlx2.bolus.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BolusFormTest {

    @Test
    fun splitByPercent_roundsToIncrement() {
        assertEquals(BolusSplit(200, 200), BolusForm.splitByPercent(400, 50))
        assertEquals(BolusSplit(250, 225), BolusForm.splitByPercent(475, 50))
        assertEquals(BolusSplit(300, 100), BolusForm.splitByPercent(400, 75))
    }

    @Test
    fun splitByPercent_edges() {
        assertEquals(BolusSplit(0, 400), BolusForm.splitByPercent(400, 0))
        assertEquals(BolusSplit(400, 0), BolusForm.splitByPercent(400, 100))
    }

    @Test
    fun splitByPercent_alwaysOnIncrementGrid() {
        val increment = BolusLimits.BOLUS_INCREMENT_MILLIUNITS
        for (total in listOf(50, 100, 400, 1_000, 24_975, 25_000)) {
            for (percent in 0..100) {
                val split = BolusForm.splitByPercent(total, percent)
                assertEquals(0, split.nowMilliunits % increment)
                assertEquals(0, split.laterMilliunits % increment)
                assertEquals(total, split.nowMilliunits + split.laterMilliunits)
            }
        }
    }

    @Test
    fun quantizeToIncrement_halfUp() {
        assertEquals(0, BolusForm.quantizeToIncrement(0))
        assertEquals(25, BolusForm.quantizeToIncrement(13))
        assertEquals(25, BolusForm.quantizeToIncrement(25))
        assertEquals(50, BolusForm.quantizeToIncrement(38))
        assertEquals(400, BolusForm.quantizeToIncrement(400))
    }

    @Test
    fun validate_acceptsMinimumAndRejectsBelow() {
        val minimum = BolusDraft(nowMilliunits = 50)
        assertTrue(BolusForm.isValid(minimum, ciqEnabled = false))

        val below = BolusDraft(nowMilliunits = 25)
        assertEquals(
            listOf(BolusValidationError.AMOUNT_BELOW_MINIMUM),
            BolusForm.validate(below, ciqEnabled = false),
        )
    }

    @Test
    fun validate_acceptsMaximumAndRejectsAbove() {
        val maximum = BolusDraft(nowMilliunits = 25_000)
        assertTrue(BolusForm.isValid(maximum, ciqEnabled = false))
        assertTrue(BolusForm.isValid(BolusDraft(nowMilliunits = 24_975), ciqEnabled = false))

        val above = BolusDraft(nowMilliunits = 25_025)
        assertEquals(
            listOf(BolusValidationError.AMOUNT_ABOVE_MAXIMUM),
            BolusForm.validate(above, ciqEnabled = false),
        )
    }

    @Test
    fun validate_rejectsOffIncrementGrid() {
        val draft = BolusDraft(nowMilliunits = 401)
        assertEquals(
            listOf(BolusValidationError.INCREMENT_NOT_ALIGNED),
            BolusForm.validate(draft, ciqEnabled = false),
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
    fun validate_extendedTotalBoundary() {
        val minimum = BolusDraft(
            nowMilliunits = 0,
            laterMilliunits = 400,
            extendedSeconds = 3_600,
            shape = BolusShape.DUAL,
        )
        assertTrue(BolusForm.isValid(minimum, ciqEnabled = false))

        val below = minimum.copy(laterMilliunits = 375)
        assertEquals(
            listOf(BolusValidationError.EXTENDED_TOTAL_BELOW_MINIMUM),
            BolusForm.validate(below, ciqEnabled = false),
        )
    }

    @Test
    fun validate_zeroImmediateAccepted() {
        // 0:100 —— 立即 0、全部延后；已真机确认可被泵接受。
        val draft = BolusDraft(
            nowMilliunits = 0,
            laterMilliunits = 400,
            extendedSeconds = 7_200,
            shape = BolusShape.DUAL,
        )
        assertTrue(BolusForm.isValid(draft, ciqEnabled = null))
        assertFalse(BolusForm.isImmediatePartUnverified(draft))
    }

    @Test
    fun validate_nonZeroImmediateBelowMinimumAllowedButFlagged() {
        // 1–49 milliunits（25 步进下即 25）未验：不硬拦，确认页提示泵可能拒绝。
        val draft = BolusDraft(
            nowMilliunits = 25,
            laterMilliunits = 375,
            extendedSeconds = 3_600,
            shape = BolusShape.SQUARE,
        )
        assertTrue(BolusForm.isValid(draft, ciqEnabled = false))
        assertTrue(BolusForm.isImmediatePartUnverified(draft))
    }

    @Test
    fun validate_squareLaterRequired() {
        val draft = BolusDraft(
            nowMilliunits = 400,
            laterMilliunits = 0,
            extendedSeconds = 3_600,
            shape = BolusShape.SQUARE,
        )
        assertTrue(BolusForm.validate(draft, ciqEnabled = false).contains(BolusValidationError.LATER_REQUIRED))
    }

    @Test
    fun validate_durationGating_ciq() {
        val draft = BolusDraft(
            nowMilliunits = 100,
            laterMilliunits = 300,
            extendedSeconds = 7_201,
            shape = BolusShape.DUAL,
        )
        assertTrue(BolusForm.validate(draft, ciqEnabled = true).contains(BolusValidationError.DURATION_OUT_OF_RANGE))
        assertFalse(BolusForm.validate(draft, ciqEnabled = false).contains(BolusValidationError.DURATION_OUT_OF_RANGE))
    }

    @Test
    fun validate_durationGating_nonCiqUpperBound() {
        val draft = BolusDraft(
            nowMilliunits = 100,
            laterMilliunits = 300,
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
