package com.pumppan.controlx2.bolus.state

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BolusFlowStateTest {

    @Test
    fun selectSource_movesFromEntryToShape() {
        val entry = BolusFlowState()
        assertEquals(BolusFlowStep.ENTRY, entry.step)

        val shape = entry.selectSource(BolusSource.MANUAL)
        assertEquals(BolusFlowStep.SHAPE, shape.step)
        assertEquals(BolusSource.MANUAL, shape.source)
    }

    @Test
    fun advance_walksFourStepsInOrder() {
        val shape = BolusFlowState(step = BolusFlowStep.SHAPE, nowMilliunits = 400)

        val confirm = shape.advance(ciqEnabled = null) as BolusAdvance.Ok
        assertEquals(BolusFlowStep.CONFIRM, confirm.state.step)

        val result = confirm.state.advance(ciqEnabled = null) as BolusAdvance.Ok
        assertEquals(BolusFlowStep.RESULT, result.state.step)
    }

    @Test
    fun advance_invalidDoseStaysOnShapeStep() {
        val shape = BolusFlowState(step = BolusFlowStep.SHAPE, nowMilliunits = 25)

        val result = shape.advance(ciqEnabled = null)
        assertTrue(result is BolusAdvance.Invalid)
        assertEquals(
            listOf(BolusValidationError.AMOUNT_BELOW_MINIMUM),
            (result as BolusAdvance.Invalid).errors,
        )
        // 状态不可变：原对象仍停在剂量步，草稿值未被清空。
        assertEquals(BolusFlowStep.SHAPE, shape.step)
        assertEquals(25, shape.nowMilliunits)
    }

    @Test
    fun advance_squareWithoutDurationStaysOnShapeStep() {
        val shape = BolusFlowState(step = BolusFlowStep.SHAPE, shape = BolusShape.SQUARE)
            .withTotal(400)
            .withDurationSeconds(0)

        val result = shape.advance(ciqEnabled = null)
        assertTrue(result is BolusAdvance.Invalid)
        assertTrue(
            (result as BolusAdvance.Invalid).errors
                .contains(BolusValidationError.DURATION_OUT_OF_RANGE),
        )
    }

    @Test
    fun withShape_normalToDual_splitsByPercent() {
        val state = BolusFlowState(step = BolusFlowStep.SHAPE, nowMilliunits = 400)

        val dual = state.withShape(BolusShape.DUAL)
        assertEquals(BolusShape.DUAL, dual.shape)
        assertEquals(200, dual.nowMilliunits)
        assertEquals(200, dual.laterMilliunits)
        assertEquals(400, dual.totalMilliunits)
        assertEquals(BolusFlowState.DEFAULT_EXTENDED_SECONDS, dual.extendedSeconds)
    }

    @Test
    fun withShape_dualToNormal_mergesLaterIntoNow() {
        val dual = BolusFlowState(
            step = BolusFlowStep.SHAPE,
            shape = BolusShape.DUAL,
            nowMilliunits = 120,
            laterMilliunits = 380,
            extendedSeconds = 5_400,
        )

        val normal = dual.withShape(BolusShape.NORMAL)
        assertEquals(BolusShape.NORMAL, normal.shape)
        assertEquals(500, normal.nowMilliunits)
        assertEquals(0, normal.laterMilliunits)
        assertEquals(0, normal.extendedSeconds)
    }

    @Test
    fun withShape_squareToDual_keepsAmounts() {
        val square = BolusFlowState(
            step = BolusFlowStep.SHAPE,
            shape = BolusShape.SQUARE,
            nowMilliunits = 100,
            laterMilliunits = 300,
            extendedSeconds = 1_800,
        )

        val dual = square.withShape(BolusShape.DUAL)
        assertEquals(BolusShape.DUAL, dual.shape)
        assertEquals(100, dual.nowMilliunits)
        assertEquals(300, dual.laterMilliunits)
        assertEquals(1_800, dual.extendedSeconds)
    }

    @Test
    fun withTotal_dualResplitsByCurrentPercent() {
        val dual = BolusFlowState(
            step = BolusFlowStep.SHAPE,
            shape = BolusShape.DUAL,
            nowMilliunits = 200,
            laterMilliunits = 200,
            extendedSeconds = 3_600,
        )

        val changed = dual.withTotal(600)
        assertEquals(300, changed.nowMilliunits)
        assertEquals(300, changed.laterMilliunits)

        val atSeventy = changed.withPercent(70).withTotal(400)
        assertEquals(275, atSeventy.nowMilliunits)
        assertEquals(125, atSeventy.laterMilliunits)
        assertEquals(70, atSeventy.percent)
    }

    @Test
    fun withPercent_resplitsDualAndKeepsTotal() {
        val dual = BolusFlowState(
            step = BolusFlowStep.SHAPE,
            shape = BolusShape.DUAL,
            nowMilliunits = 200,
            laterMilliunits = 200,
            extendedSeconds = 3_600,
        )

        val atThirty = dual.withPercent(30)
        assertEquals(125, atThirty.nowMilliunits)
        assertEquals(275, atThirty.laterMilliunits)
        assertEquals(dual.totalMilliunits, atThirty.totalMilliunits)
    }

    @Test
    fun withPercent_zero_splitsAllToLaterAndAdvances() {
        val dual = BolusFlowState(
            step = BolusFlowStep.SHAPE,
            shape = BolusShape.DUAL,
            nowMilliunits = 200,
            laterMilliunits = 200,
            extendedSeconds = 3_600,
        )

        val zero = dual.withPercent(0)
        assertEquals(0, zero.nowMilliunits)
        assertEquals(400, zero.laterMilliunits)

        val confirm = zero.advance(ciqEnabled = null)
        assertTrue(confirm is BolusAdvance.Ok)
        assertEquals(BolusFlowStep.CONFIRM, (confirm as BolusAdvance.Ok).state.step)
    }

    @Test
    fun advance_extendedTotalBoundary() {
        val atMinimum = BolusFlowState(step = BolusFlowStep.SHAPE, shape = BolusShape.DUAL)
            .withPercent(0)
            .withTotal(400)
            .withDurationSeconds(3_600)
        assertEquals(400, atMinimum.totalMilliunits)
        assertEquals(0, atMinimum.nowMilliunits)
        assertTrue(atMinimum.advance(ciqEnabled = null) is BolusAdvance.Ok)

        val below = atMinimum.withTotal(375)
        val result = below.advance(ciqEnabled = null)
        assertTrue(result is BolusAdvance.Invalid)
        assertTrue(
            (result as BolusAdvance.Invalid).errors
                .contains(BolusValidationError.EXTENDED_TOTAL_BELOW_MINIMUM),
        )
    }

    @Test
    fun back_keepsDraftWhileMovingBetweenSteps() {
        val confirm = BolusFlowState(
            step = BolusFlowStep.CONFIRM,
            shape = BolusShape.DUAL,
            nowMilliunits = 150,
            laterMilliunits = 250,
            extendedSeconds = 3_600,
        )

        val shape = confirm.back()
        assertEquals(BolusFlowStep.SHAPE, shape.step)
        assertEquals(150, shape.nowMilliunits)
        assertEquals(250, shape.laterMilliunits)
        assertEquals(3_600, shape.extendedSeconds)

        val entry = shape.back()
        assertEquals(BolusFlowStep.ENTRY, entry.step)
        assertEquals(150, entry.nowMilliunits)

        // 返回入口再选「直接输入」时，草稿值原样保留。
        val again = entry.selectSource(BolusSource.MANUAL)
        assertEquals(confirm.copy(step = BolusFlowStep.SHAPE), again)
    }

    @Test
    fun saver_roundTripsDraft() {
        val state = BolusFlowState(
            step = BolusFlowStep.CONFIRM,
            source = BolusSource.MANUAL,
            shape = BolusShape.DUAL,
            nowMilliunits = 120,
            laterMilliunits = 380,
            extendedSeconds = 5_400,
            percent = 24,
        )

        val scope = SaverScope { true }
        val saved = with(BolusFlowState.Saver) { scope.save(state) }
        assertNotNull(saved)

        val restored = BolusFlowState.Saver.restore(saved!!)
        assertEquals(state, restored)
        assertEquals(BolusFlowStep.CONFIRM, restored?.step)
    }

    @Test
    fun saver_roundTripsFreshDraft() {
        val state = BolusFlowState()
        val scope = SaverScope { true }
        val saved = with(BolusFlowState.Saver) { scope.save(state) }
        assertNotNull(saved)
        assertEquals(state, BolusFlowState.Saver.restore(saved!!))
    }
}
