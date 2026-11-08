package com.pumppan.controlx2.bolus.wizard

import com.pumppan.controlx2.bolus.state.WizardConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 血糖单位与三态校验。
 *
 * - 内部只存一个 mg/dL 整数；mmol 只是显示换算（1 mmol/L ≈ 18 mg/dL）。
 * - mg/dL 禁小数点；mmol 强制一位小数（13 也显示 13.0）。
 * - 合法区间按单位分别判，全部来自配置。
 * - 非法原因只陈述事实，不做「这看起来是 mg/dL」之类的推断。
 */
class BolusWizardGlucoseTest {

    private val config = WizardConfig.Default

    @Test
    fun format_mgdlIsInteger() {
        assertEquals("100", BolusWizardGlucose.format(100, GlucoseUnit.MG_DL))
        assertEquals("600", BolusWizardGlucose.format(600, GlucoseUnit.MG_DL))
    }

    @Test
    fun format_mmolAlwaysOneDecimal() {
        assertEquals("13.0", BolusWizardGlucose.format(234, GlucoseUnit.MMOL_L))
        assertEquals("5.6", BolusWizardGlucose.format(100, GlucoseUnit.MMOL_L))
        assertEquals("3.0", BolusWizardGlucose.format(54, GlucoseUnit.MMOL_L))
    }

    @Test
    fun parseMgdl_validRange() {
        assertEquals(WizardField(WizardFieldStatus.VALID, 400), BolusWizardGlucose.parse("400", GlucoseUnit.MG_DL, config))
        assertEquals(WizardField(WizardFieldStatus.VALID, 20), BolusWizardGlucose.parse("20", GlucoseUnit.MG_DL, config))
        assertEquals(WizardField(WizardFieldStatus.VALID, 600), BolusWizardGlucose.parse("600", GlucoseUnit.MG_DL, config))
    }

    @Test
    fun parseMgdl_emptyIsNotZero() {
        val blank = BolusWizardGlucose.parse("", GlucoseUnit.MG_DL, config)
        assertEquals(WizardFieldStatus.EMPTY, blank.status)
        assertEquals(null, blank.value)
    }

    @Test
    fun parseMgdl_rejectsDecimal() {
        val field = BolusWizardGlucose.parse("13.0", GlucoseUnit.MG_DL, config)
        assertEquals(WizardFieldStatus.INVALID, field.status)
        assertEquals(WizardFieldIssue.DECIMAL_NOT_ALLOWED, field.issue)
    }

    @Test
    fun parseMgdl_rejectsNonNumber() {
        val field = BolusWizardGlucose.parse("abc", GlucoseUnit.MG_DL, config)
        assertEquals(WizardFieldStatus.INVALID, field.status)
        assertEquals(WizardFieldIssue.NOT_A_NUMBER, field.issue)
    }

    @Test
    fun parseMgdl_outOfRange() {
        assertEquals(
            WizardFieldIssue.BELOW_MIN,
            BolusWizardGlucose.parse("19", GlucoseUnit.MG_DL, config).issue,
        )
        assertEquals(
            WizardFieldIssue.ABOVE_MAX,
            BolusWizardGlucose.parse("601", GlucoseUnit.MG_DL, config).issue,
        )
    }

    @Test
    fun parseMmol_convertsToCanonicalMgdl() {
        // 13.0 mmol/L × 18 = 234 mg/dL
        assertEquals(WizardField(WizardFieldStatus.VALID, 234), BolusWizardGlucose.parse("13.0", GlucoseUnit.MMOL_L, config))
        // 5.6 mmol/L × 18 = 100.8 → 101
        assertEquals(WizardField(WizardFieldStatus.VALID, 101), BolusWizardGlucose.parse("5.6", GlucoseUnit.MMOL_L, config))
    }

    @Test
    fun parseMmol_rangeIsJudgedInMmol() {
        assertEquals(
            WizardFieldIssue.BELOW_MIN,
            BolusWizardGlucose.parse("1.0", GlucoseUnit.MMOL_L, config).issue,
        )
        assertEquals(
            WizardFieldIssue.ABOVE_MAX,
            BolusWizardGlucose.parse("33.4", GlucoseUnit.MMOL_L, config).issue,
        )
        assertEquals(WizardFieldStatus.VALID, BolusWizardGlucose.parse("33.3", GlucoseUnit.MMOL_L, config).status)
    }

    @Test
    fun parseMmol_rejectsNonNumber() {
        assertEquals(
            WizardFieldIssue.NOT_A_NUMBER,
            BolusWizardGlucose.parse("abc", GlucoseUnit.MMOL_L, config).issue,
        )
    }
}
