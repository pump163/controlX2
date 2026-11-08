package com.pumppan.controlx2.bolus.wizard

import com.pumppan.controlx2.bolus.state.WizardConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计算器口径（Tandem）：
 * - 食物量 = 碳水 / ICR。
 * - 血糖高于目标：校正 = (BG − 目标) / ISF − IOB，扣完小于 0 按 0。
 * - 血糖在 [lowBg, 目标]：不校正、不扣 IOB。
 * - 血糖低于 lowBg：校正为负，IOB 继续参与扣减，总量最低 0。
 * - 读不到血糖：只按食物量计算（空不等于 0）。
 * 入口复查：血糖必须是整数 mg/dL；带小数时四舍五入照常计算，只留诊断标志。
 */
class BolusWizardCalculatorTest {

    private val config = WizardConfig.Default
    /** 与 [params] 的接收时间一致：默认视为新鲜。 */
    private val now = 0L

    private val params = WizardPumpParams(
        carbRatioGramsPerUnit = 6.0,
        isfMgdlPerUnit = 50,
        targetBgMgdl = 120,
        maxBolusMilliunits = 25_000,
        carbEntryEnabled = true,
        atMillis = 0L,
    )

    private fun iob(milliunits: Int) = WizardIob(
        milliunits = milliunits,
        atMillis = now,
        source = WizardIobSource.CURRENT_QUERY,
    )

    @Test
    fun foodOnly_whenNoGlucose() {
        val result = BolusWizardCalculator.calculate(60.0, null, params, config)
        assertEquals(10_000, result.foodMilliunits)
        assertEquals(0, result.correctionMilliunits)
        assertEquals(10_000, result.totalMilliunits)
        assertFalse(result.belowLow)
        assertFalse(result.clampedToZero)
    }

    @Test
    fun atTarget_correctionIsZero() {
        val result = BolusWizardCalculator.calculate(60.0, 120.0, params, config)
        assertEquals(10_000, result.foodMilliunits)
        assertEquals(0, result.correctionMilliunits)
        assertEquals(10_000, result.totalMilliunits)
    }

    @Test
    fun aboveTarget_correctionMinusIob() {
        // (220 − 120) / 50 = 2.0U，扣 IOB 0.5U → 1.5U
        val result = BolusWizardCalculator.calculate(
            carbsGrams = 0.0,
            glucoseMgdl = 220.0,
            params = params,
            config = config,
            iob = iob(500),
        )
        assertEquals(0, result.foodMilliunits)
        assertEquals(1_500, result.correctionMilliunits)
        assertEquals(1_500, result.totalMilliunits)
        assertEquals(500, result.iobMilliunits)
    }

    @Test
    fun aboveTarget_iobExceedsCorrection_clampsCorrectionToZero() {
        // 2.0U 校正被 2.5U IOB 全部抵消，校正不为负
        val result = BolusWizardCalculator.calculate(
            carbsGrams = 0.0,
            glucoseMgdl = 220.0,
            params = params,
            config = config,
            iob = iob(2_500),
        )
        assertEquals(0, result.correctionMilliunits)
        assertEquals(0, result.totalMilliunits)
        assertFalse(result.clampedToZero)
    }

    @Test
    fun betweenLowAndTarget_noCorrectionNoIob() {
        val result = BolusWizardCalculator.calculate(
            carbsGrams = 60.0,
            glucoseMgdl = 90.0,
            params = params,
            config = config,
            iob = iob(1_000),
        )
        assertEquals(10_000, result.foodMilliunits)
        assertEquals(0, result.correctionMilliunits)
        assertEquals(10_000, result.totalMilliunits)
        assertFalse(result.belowLow)
    }

    @Test
    fun belowLow_correctionNegative() {
        // (60 − 120) / 50 = −1.2U；食物 10U − 1.2U = 8.8U
        val result = BolusWizardCalculator.calculate(60.0, 60.0, params, config)
        assertEquals(10_000, result.foodMilliunits)
        assertEquals(-1_200, result.correctionMilliunits)
        assertEquals(8_800, result.totalMilliunits)
        assertTrue(result.belowLow)
    }

    @Test
    fun belowLow_totalClampedToZero() {
        // 食物 5U；校正 −1.2U − 13U IOB = −14.2U → 总量截到 0
        val result = BolusWizardCalculator.calculate(
            carbsGrams = 30.0,
            glucoseMgdl = 60.0,
            params = params,
            config = config,
            iob = iob(13_000),
        )
        assertEquals(0, result.totalMilliunits)
        assertEquals(-14_200, result.correctionMilliunits)
        assertTrue(result.belowLow)
        assertTrue(result.clampedToZero)
    }

    @Test
    fun normalizeInput_roundsFractionalGlucose() {
        // 上游换算漏出小数：四舍五入、不抛异常，只打诊断标志
        val input = BolusWizardCalculator.normalizeInput(60.4, 244.8, config)
        assertEquals(60, input.carbsGrams)
        assertEquals(245, input.glucoseMgdl)
        assertTrue(input.glucoseWasFractional)
    }

    @Test
    fun normalizeInput_integerGlucoseIsNotFractional() {
        val input = BolusWizardCalculator.normalizeInput(60.0, 244.0, config)
        assertEquals(244, input.glucoseMgdl)
        assertFalse(input.glucoseWasFractional)
    }

    @Test
    fun normalizeInput_noGlucoseStaysNull() {
        assertNull(BolusWizardCalculator.normalizeInput(60.0, null, config).glucoseMgdl)
        // 0 / 负值是「没有读数」，不是低血糖
        assertNull(BolusWizardCalculator.normalizeInput(60.0, 0.0, config).glucoseMgdl)
        assertNull(BolusWizardCalculator.normalizeInput(60.0, -5.0, config).glucoseMgdl)
    }

    @Test
    fun normalizeInput_clampsCarbsAndRounds() {
        assertEquals(config.maxCarbsGrams, BolusWizardCalculator.normalizeInput(config.maxCarbsGrams + 50.0, null, config).carbsGrams)
        assertEquals(0, BolusWizardCalculator.normalizeInput(-5.0, null, config).carbsGrams)
        assertEquals(61, BolusWizardCalculator.normalizeInput(60.5, null, config).carbsGrams)
    }

    @Test
    fun fractionalGlucoseStillCalculates() {
        // 守卫不得中断计算：244.8 按 245 参与
        val result = BolusWizardCalculator.calculate(0.0, 244.8, params, config)
        assertEquals(2_500, result.correctionMilliunits) // (245 − 120) / 50 = 2.5U
        assertEquals(2_500, result.totalMilliunits)
    }

    @Test
    fun toPayload_echoesInputs() {
        val result = BolusWizardCalculator.calculate(60.0, 220.0, params, config)
        val payload = result.toPayload(carbsGrams = 60, glucoseMgdl = 220)
        assertEquals(result.foodMilliunits, payload.foodMilliunits)
        assertEquals(result.correctionMilliunits, payload.correctionMilliunits)
        assertEquals(60, payload.carbsGrams)
        assertEquals(220, payload.bgMgdl)
        assertEquals(result.iobMilliunits, payload.iobMilliunits)
    }

    @Test
    fun formulaTermsAreExposedForDisplay() {
        // 公式三段要能对上：gross（除法结果）− 实扣 IOB = net
        val result = BolusWizardCalculator.calculate(
            carbsGrams = 0.0,
            glucoseMgdl = 220.0,
            params = params,
            config = config,
            iob = iob(500),
        )
        assertEquals(2_000, result.correctionGrossMilliunits) // (220−120)/50 = 2.0U
        assertEquals(1_500, result.correctionMilliunits)
        assertEquals(500, result.iobDeductedMilliunits)
        assertTrue(result.iobApplied)
        assertFalse(result.iobStale)
        assertEquals(500, result.iobMilliunits)
        assertEquals(220, result.glucoseMgdl)
    }

    @Test
    fun belowLow_keepsNegativeSignVisible() {
        val result = BolusWizardCalculator.calculate(60.0, 60.0, params, config)
        assertTrue(result.correctionMilliunits < 0)
        assertEquals(-1_200, result.correctionGrossMilliunits)
        assertEquals(-1_200, result.correctionMilliunits)
        assertEquals(0, result.iobDeductedMilliunits) // 没有 IOB 可扣
    }

    @Test
    fun inTargetBand_noCorrectionAndIobNotApplied() {
        val result = BolusWizardCalculator.calculate(
            carbsGrams = 30.0,
            glucoseMgdl = 100.0,
            params = params,
            config = config,
            iob = iob(1_000),
        )
        assertEquals(0, result.correctionMilliunits)
        assertEquals(0, result.iobDeductedMilliunits)
        assertFalse(result.iobApplied)
        assertTrue(result.correctionInRange)
    }

    @Test
    fun missingCarbsKeepsNullInsteadOfZero() {
        val result = BolusWizardCalculator.calculate(null, 180.0, params, config)
        assertNull(result.carbsGrams)
        assertEquals(0, result.foodMilliunits)
        // 未填碳水不影响校正段：只做校正
        assertEquals(1_200, result.correctionMilliunits) // (180−120)/50 = 1.2U
    }

    @Test
    fun missingGlucoseSkipsCorrection() {
        val result = BolusWizardCalculator.calculate(60.0, null, params, config)
        assertNull(result.glucoseMgdl)
        assertEquals(0, result.correctionMilliunits)
        assertFalse(result.correctionInRange)
        assertEquals(10_000, result.totalMilliunits)
    }

    @Test
    fun unavailableIobIsNotDeducted() {
        // 快照层判「本次不可用」后传 null：不显示数值、不参与运算。
        val result = BolusWizardCalculator.calculate(
            carbsGrams = 0.0,
            glucoseMgdl = 220.0,
            params = params,
            config = config,
            iob = null,
            iobUnavailable = WizardIobUnavailableReason.EXPIRED_CACHE,
        )
        assertTrue(result.iobStale)
        assertEquals(WizardIobUnavailableReason.EXPIRED_CACHE, result.iobUnavailable)
        assertTrue(result.iobApplied) // 该段本来要用 IOB，但不可用后按 0 处理
        assertEquals(0, result.iobMilliunits) // 不可用时不再回显原始值
        assertEquals(0, result.iobDeductedMilliunits)
        assertEquals(2_000, result.correctionMilliunits) // 未扣 IOB
        assertEquals(2_000, result.totalMilliunits)
    }

    @Test
    fun infusionVetoIsCarriedIntoResult() {
        val result = BolusWizardCalculator.calculate(
            carbsGrams = 0.0,
            glucoseMgdl = 220.0,
            params = params,
            config = config,
            iob = null,
            iobUnavailable = WizardIobUnavailableReason.INFUSION_ACTIVITY,
        )
        assertTrue(result.iobStale)
        assertEquals(WizardIobUnavailableReason.INFUSION_ACTIVITY, result.iobUnavailable)
        assertEquals(0, result.iobDeductedMilliunits)
        assertEquals(2_000, result.totalMilliunits)
    }

    @Test
    fun freshIobIsDeducted() {
        val result = BolusWizardCalculator.calculate(
            carbsGrams = 0.0,
            glucoseMgdl = 220.0,
            params = params,
            config = config,
            iob = iob(2_000),
        )
        assertFalse(result.iobStale)
        assertEquals(2_000, result.iobDeductedMilliunits) // 2.0U 校正被 2.0U IOB 全抵
        assertEquals(0, result.totalMilliunits)
    }

    @Test
    fun staleIobIsNotWrittenIntoPayload() {
        val stale = WizardCalcResult(
            totalMilliunits = 2_000,
            foodMilliunits = 0,
            correctionMilliunits = 2_000,
            iobMilliunits = 2_000,
            belowLow = false,
            clampedToZero = false,
            iobStale = true,
        )
        assertEquals(0, stale.toPayload(0, 220).iobMilliunits)
    }
}
