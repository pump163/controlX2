package com.pumppan.controlx2.bolus.wizard

import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.WizardConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 多步状态容器：脱离页面组合存活，跨标签保留；只有 X / 完成 / 成功发送才清空。
 * 血糖只保留一个 canonical mg/dL 值，换单位从它重排，来回切换不走样。
 */
class BolusWizardSessionTest {

    private val config = WizardConfig.Default
    private val limits = BolusLimits.Default
    private val now = 1_000_000L

    private val params = WizardPumpParams(
        carbRatioGramsPerUnit = 6.0,
        isfMgdlPerUnit = 50,
        targetBgMgdl = 120,
        maxBolusMilliunits = 25_000,
        carbEntryEnabled = true,
        atMillis = now,
    )

    @Before
    @After
    fun resetRuntime() {
        BolusWizardRuntime.clear()
    }

    @Test
    fun inputsSurviveWithoutPageComposition() {
        // 状态在单例容器里，不在组合里：模拟「离开页面」后依旧读到原输入
        BolusWizardRuntime.update { it.withCarbs("45") }
        BolusWizardRuntime.update { it.withBg("180", config) }
        assertEquals("45", BolusWizardRuntime.state.carbsText)
        assertEquals("180", BolusWizardRuntime.state.bgText)
        assertEquals(180, BolusWizardRuntime.state.bgMgdl)
    }

    @Test
    fun unitSwitch_roundTripKeepsCanonicalValue() {
        // 100 mg/dL → 5.6 mmol/L → 100 mg/dL（不能变成 101）
        var state = BolusWizardState().withBg("100", config)
        assertEquals(100, state.bgMgdl)

        state = state.withBgUnit(GlucoseUnit.MMOL_L, config)
        assertEquals("5.6", state.bgText)
        assertEquals(100, state.bgMgdl)

        state = state.withBgUnit(GlucoseUnit.MG_DL, config)
        assertEquals("100", state.bgText)
        assertEquals(100, state.bgMgdl)
    }

    @Test
    fun unitSwitch_editingInMmolKeepsUserText() {
        var state = BolusWizardState().withBgUnit(GlucoseUnit.MMOL_L, config)
        state = state.withBg("13.0", config)
        assertEquals(234, state.bgMgdl)
        assertEquals("13.0", state.bgText)

        state = state.withBgUnit(GlucoseUnit.MG_DL, config)
        assertEquals("234", state.bgText)
    }

    @Test
    fun unitSwitch_invalidTextIsPreserved() {
        val state = BolusWizardState().withBg("abc", config)
        assertNull(state.bgMgdl)
        val switched = state.withBgUnit(GlucoseUnit.MMOL_L, config)
        assertEquals("abc", switched.bgText)
        assertEquals(GlucoseUnit.MMOL_L, switched.bgUnit)
    }

    @Test
    fun manualEdit_detachesFromCgm() {
        var state = BolusWizardState().prefillFromCgm(WizardGlucose(150, now, stale = false))
        assertTrue(state.bgFromCgm)
        assertEquals(150, state.bgMgdl)

        state = state.withBg("160", config)
        assertFalse(state.bgFromCgm)
        assertTrue(state.bgTouched)
        assertEquals(160, state.bgMgdl)
    }

    @Test
    fun prefillDoesNotOverwriteTouchedField() {
        val touched = BolusWizardState().withBg("160", config)
        val after = touched.prefillFromCgm(WizardGlucose(200, now, stale = false))
        assertEquals("160", after.bgText)
        assertEquals(160, after.bgMgdl)
    }

    @Test
    fun prefillIgnoresUnavailableGlucose() {
        val after = BolusWizardState().prefillFromCgm(WizardGlucose(null, null, stale = true))
        assertEquals("", after.bgText)
        assertNull(after.bgMgdl)
    }

    @Test
    fun prefillUsesCurrentUnit() {
        val state = BolusWizardState()
            .withBgUnit(GlucoseUnit.MMOL_L, config)
            .prefillFromCgm(WizardGlucose(234, now, stale = false))
        assertEquals("13.0", state.bgText)
        assertEquals(234, state.bgMgdl)
    }

    @Test
    fun resultFreezesInputsAndBackKeepsThem() {
        val result = WizardCalcResult(
            totalMilliunits = 10_000,
            foodMilliunits = 10_000,
            correctionMilliunits = 0,
            iobMilliunits = 0,
            belowLow = false,
            clampedToZero = false,
            carbsGrams = 60,
        )
        val state = BolusWizardState()
            .withCarbs("60")
            .withBg("120", config)
            .showResult(
                result = result,
                bgMgdl = 120,
                bgFromCgm = false,
                bgAtMillis = null,
                params = params,
                atMillis = now,
            )
        assertEquals(BolusWizardStep.RESULT, state.step)
        assertEquals(120, state.resultBgMgdl)
        assertEquals(60, state.resultCarbsGrams)
        assertEquals(params, state.resultParams)

        // 返回输入页：原输入仍在（核对填错项的前提）
        val back = state.backToInput()
        assertEquals(BolusWizardStep.INPUT, back.step)
        assertEquals("60", back.carbsText)
        assertEquals("120", back.bgText)
        assertEquals(120, back.bgMgdl)
    }

    @Test
    fun clearedOnlyByExplicitClear() {
        BolusWizardRuntime.update { it.withCarbs("60") }
        BolusWizardRuntime.update { it.withBg("120", config) }
        assertEquals("60", BolusWizardRuntime.state.carbsText)

        // 切标签不调用 clear：状态仍在
        assertEquals("120", BolusWizardRuntime.state.bgText)

        BolusWizardRuntime.clear()
        assertEquals("", BolusWizardRuntime.state.carbsText)
        assertEquals("", BolusWizardRuntime.state.bgText)
        assertEquals(BolusWizardStep.INPUT, BolusWizardRuntime.state.step)
    }

    @Test
    fun calcPayload_reflectsFrozenResult() {
        val result = WizardCalcResult(
            totalMilliunits = 8_800,
            foodMilliunits = 10_000,
            correctionMilliunits = -1_200,
            iobMilliunits = 0,
            belowLow = true,
            clampedToZero = false,
            carbsGrams = 60,
        )
        val payload = BolusWizardState()
            .showResult(result, 60, false, null, params, now)
            .calcPayload
        assertEquals(10_000, payload?.foodMilliunits)
        assertEquals(-1_200, payload?.correctionMilliunits)
        assertEquals(60, payload?.carbsGrams)
        assertEquals(60, payload?.bgMgdl)
    }

    @Test
    fun calcPayload_isNullBeforeCalculation() {
        assertNull(BolusWizardState().calcPayload)
    }

    @Test
    fun editedTotalKeepsSuggestedForComparison() {
        val result = WizardCalcResult(
            totalMilliunits = 3_600,
            foodMilliunits = 3_000,
            correctionMilliunits = 600,
            iobMilliunits = 0,
            belowLow = false,
            clampedToZero = false,
        )
        val state = BolusWizardState()
            .showResult(result, null, false, null, params, now)
            .withEditedTotal("4.00", limits)

        assertEquals(4_000, state.actualTotalMilliunits)
        assertEquals(3_600, state.result?.totalMilliunits) // 建议值不被覆盖
        assertTrue(state.totalEdited)
    }

    @Test
    fun blankEditFallsBackToSuggestion() {
        val result = WizardCalcResult(
            totalMilliunits = 3_600,
            foodMilliunits = 3_600,
            correctionMilliunits = 0,
            iobMilliunits = 0,
            belowLow = false,
            clampedToZero = false,
        )
        val state = BolusWizardState()
            .showResult(result, null, false, null, params, now)
            .withEditedTotal("", limits)

        assertEquals(3_600, state.actualTotalMilliunits)
        assertFalse(state.totalEdited)
    }

    @Test
    fun invalidEditDoesNotBecomeActual() {
        val result = WizardCalcResult(
            totalMilliunits = 3_600,
            foodMilliunits = 3_600,
            correctionMilliunits = 0,
            iobMilliunits = 0,
            belowLow = false,
            clampedToZero = false,
        )
        val state = BolusWizardState()
            .showResult(result, null, false, null, params, now)
            .withEditedTotal("4.03", limits)

        // 非法输入不推进：实际值仍是建议值，界面靠三态拦住确认
        assertEquals(3_600, state.actualTotalMilliunits)
        assertFalse(state.totalEdited)
    }

    @Test
    fun newCalculationResetsPreviousEdit() {
        val first = WizardCalcResult(
            totalMilliunits = 3_600,
            foodMilliunits = 3_600,
            correctionMilliunits = 0,
            iobMilliunits = 0,
            belowLow = false,
            clampedToZero = false,
        )
        val state = BolusWizardState()
            .showResult(first, null, false, null, params, now)
            .withEditedTotal("4.00", limits)
            .showResult(first, null, false, null, params, now)

        assertEquals("", state.editedTotalText)
        assertNull(state.editedTotalMilliunits)
    }
}
