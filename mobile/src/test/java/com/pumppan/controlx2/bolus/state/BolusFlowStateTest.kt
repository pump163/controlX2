package com.pumppan.controlx2.bolus.state

import androidx.compose.runtime.saveable.SaverScope
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BolusFlowStateTest {

    @Test
    fun defaultStepIsShape_flowStartsAtInput() {
        // 原「入口选择」步已取消：草稿一建立就停在输入页。
        val entry = BolusFlowState()
        assertEquals(BolusFlowStep.SHAPE, entry.step)

        val preset = entry.withSource(BolusSource.PRESET)
        assertEquals(BolusFlowStep.SHAPE, preset.step)
        assertEquals(BolusSource.PRESET, preset.source)
    }

    @Test
    fun advance_walksThreeStepsInOrder() {
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
        val x2 = BolusLimits.forModel(KnownDeviceModel.TSLIM_X2)
        val dual = BolusFlowState(
            step = BolusFlowStep.SHAPE,
            shape = BolusShape.DUAL,
            nowMilliunits = 200,
            laterMilliunits = 200,
            extendedSeconds = 3_600,
        )

        val changed = dual.withTotal(600, x2)
        assertEquals(300, changed.nowMilliunits)
        assertEquals(300, changed.laterMilliunits)

        // X2 是 50 网格：400@70% → 300 / 100（25 网格下的 275 / 125 在 X2 上打不出来）。
        val atSeventy = changed.withPercent(70, x2).withTotal(400, x2)
        assertEquals(300, atSeventy.nowMilliunits)
        assertEquals(100, atSeventy.laterMilliunits)
        assertEquals(70, atSeventy.percent)
    }

    @Test
    fun withPercent_resplitsDualAndKeepsTotal() {
        val x2 = BolusLimits.forModel(KnownDeviceModel.TSLIM_X2)
        val dual = BolusFlowState(
            step = BolusFlowStep.SHAPE,
            shape = BolusShape.DUAL,
            nowMilliunits = 200,
            laterMilliunits = 200,
            extendedSeconds = 3_600,
        )

        // X2：400@30% → 立即 100 / 延后 300。
        val atThirty = dual.withPercent(30, x2)
        assertEquals(100, atThirty.nowMilliunits)
        assertEquals(300, atThirty.laterMilliunits)
        assertEquals(dual.totalMilliunits, atThirty.totalMilliunits)
    }

    @Test
    fun resplit_staysOnGrid_perModelTier() {
        val tiers = listOf(
            "X2(50)" to BolusLimits.forModel(KnownDeviceModel.TSLIM_X2),
            "Mobi(50)" to BolusLimits.forModel(KnownDeviceModel.MOBI),
            "unknown(25)" to BolusLimits.forModel(null),
            "old-pump(10)" to BolusLimits(bolusIncrementMilliunits = 10),
        )
        for ((name, limits) in tiers) {
            val inc = limits.bolusIncrementMilliunits
            val total = inc * 8
            val dual = BolusFlowState(step = BolusFlowStep.SHAPE, nowMilliunits = total)
                .withExtended(true, limits)
            assertEquals("$name: 开扩展后总量守恒", total, dual.totalMilliunits)
            assertEquals("$name: 立即段在网格上", 0, dual.nowMilliunits % inc)
            assertEquals("$name: 延后段在网格上", 0, dual.laterMilliunits % inc)

            for (percent in listOf(0, 25, 30, 50, 70, 75, 100)) {
                val resplit = dual.withPercent(percent, limits)
                assertEquals("$name: 改百分比后总量守恒", total, resplit.totalMilliunits)
                assertEquals("$name: 立即段在网格上", 0, resplit.nowMilliunits % inc)
                assertEquals("$name: 延后段在网格上", 0, resplit.laterMilliunits % inc)

                val grown = resplit.withTotal(total + inc * 2, limits)
                assertEquals("$name: 改总量后守恒", total + inc * 2, grown.totalMilliunits)
                assertEquals("$name: 立即段在网格上", 0, grown.nowMilliunits % inc)
                assertEquals("$name: 延后段在网格上", 0, grown.laterMilliunits % inc)
            }
        }
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

        // 立即段 0 属 Mobi 口径（X2 要求 ≥50 mIU），用 minImmediate=0 的副本验证推进。
        val confirm = zero.advance(ciqEnabled = null, BolusLimits.Default.copy(minImmediateMilliunits = 0))
        assertTrue(confirm is BolusAdvance.Ok)
        assertEquals(BolusFlowStep.CONFIRM, (confirm as BolusAdvance.Ok).state.step)
    }

    @Test
    fun advance_extendedTotalBoundary() {
        // 立即段 0 属 Mobi 口径；用 minImmediate=0 的副本隔离该分叉。
        val mobi = BolusLimits.Default.copy(minImmediateMilliunits = 0)
        val atMinimum = BolusFlowState(step = BolusFlowStep.SHAPE, shape = BolusShape.DUAL)
            .withPercent(0, mobi)
            .withTotal(400, mobi)
            .withDurationSeconds(3_600)
        assertEquals(400, atMinimum.totalMilliunits)
        assertEquals(0, atMinimum.nowMilliunits)
        assertTrue(atMinimum.advance(ciqEnabled = null, mobi) is BolusAdvance.Ok)

        val below = atMinimum.withTotal(375, mobi)
        val result = below.advance(ciqEnabled = null, mobi)
        assertTrue(result is BolusAdvance.Invalid)
        assertTrue(
            (result as BolusAdvance.Invalid).errors
                .contains(BolusValidationError.EXTENDED_TOTAL_BELOW_MINIMUM),
        )

        // 50 网格（X2 精度）。X2 本身不允许远程发起扩展（能力由配置声明），
        // 这里只验证 50 网格下的总量下限，故显式副本保留 remoteExtendedBolusStart = true、
        // 并把 minImmediate 置 0 以隔离立即段下限（该分叉由 BolusFormTest 覆盖）。
        val x2 = BolusLimits.Default.copy(bolusIncrementMilliunits = 50, minImmediateMilliunits = 0)
        val x2AtMinimum = BolusFlowState(step = BolusFlowStep.SHAPE, shape = BolusShape.DUAL)
            .withPercent(0, x2)
            .withTotal(400, x2)
            .withDurationSeconds(3_600)
        assertEquals(400, x2AtMinimum.laterMilliunits)
        assertTrue(x2AtMinimum.advance(ciqEnabled = null, x2) is BolusAdvance.Ok)

        val x2Below = x2AtMinimum.withTotal(350, x2)
        val x2Result = x2Below.advance(ciqEnabled = null, x2)
        assertTrue(x2Result is BolusAdvance.Invalid)
        assertTrue(
            (x2Result as BolusAdvance.Invalid).errors
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

        // 输入页没有上一步：back() 原地不动，退出流程由页面负责（回到来源标签）。
        val atShape = shape.back()
        assertEquals(BolusFlowStep.SHAPE, atShape.step)
        assertEquals(150, atShape.nowMilliunits)
        assertEquals(250, atShape.laterMilliunits)
        assertEquals(3_600, atShape.extendedSeconds)
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
