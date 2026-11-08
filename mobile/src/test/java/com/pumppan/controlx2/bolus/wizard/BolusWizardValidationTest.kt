package com.pumppan.controlx2.bolus.wizard

import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.WizardConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #61 三态：空 / 非法 / 合法挂在字段值上。「空」不是 0，不能直接参与计算。
 */
class BolusWizardValidationTest {

    private val config = WizardConfig.Default
    private val limits = BolusLimits.Default

    @Test
    fun blankIsEmptyNotZero() {
        for (text in listOf("", "  ")) {
            val field = BolusWizardValidation.carbs(text, config)
            assertEquals(WizardFieldStatus.EMPTY, field.status)
            assertNull(field.value)
        }
    }

    @Test
    fun validCarbsCarriesValue() {
        assertEquals(WizardField(WizardFieldStatus.VALID, 45), BolusWizardValidation.carbs("45", config))
        assertEquals(WizardField(WizardFieldStatus.VALID, 0), BolusWizardValidation.carbs("0", config))
        assertEquals(
            WizardField(WizardFieldStatus.VALID, config.maxCarbsGrams),
            BolusWizardValidation.carbs(config.maxCarbsGrams.toString(), config),
        )
    }

    @Test
    fun aboveMaxIsInvalid() {
        val field = BolusWizardValidation.carbs((config.maxCarbsGrams + 1).toString(), config)
        assertEquals(WizardFieldStatus.INVALID, field.status)
        assertEquals(WizardFieldIssue.ABOVE_MAX, field.issue)
        assertNull(field.value)
    }

    @Test
    fun belowMinIsInvalid() {
        val field = BolusWizardValidation.carbs("-5", config)
        assertEquals(WizardFieldStatus.INVALID, field.status)
        assertEquals(WizardFieldIssue.BELOW_MIN, field.issue)
    }

    @Test
    fun nonNumberIsInvalid() {
        for (text in listOf("abc", "4.5", "45g")) {
            val field = BolusWizardValidation.carbs(text, config)
            assertEquals("text=$text", WizardFieldStatus.INVALID, field.status)
            assertEquals("text=$text", WizardFieldIssue.NOT_A_NUMBER, field.issue)
        }
    }

    @Test
    fun limitComesFromConfig() {
        val narrow = config.copy(maxCarbsGrams = 100)
        assertEquals(WizardFieldStatus.VALID, BolusWizardValidation.carbs("100", narrow).status)
        assertEquals(
            WizardFieldIssue.ABOVE_MAX,
            BolusWizardValidation.carbs("101", narrow).issue,
        )
    }

    @Test
    fun doseBlankFallsBackToSuggested() {
        assertEquals(WizardFieldStatus.EMPTY, BolusWizardValidation.dose("", limits).status)
        assertEquals(WizardFieldStatus.EMPTY, BolusWizardValidation.dose("  ", limits).status)
    }

    @Test
    fun doseValidWhenAlignedAndInRange() {
        assertEquals(WizardField(WizardFieldStatus.VALID, 4_000), BolusWizardValidation.dose("4", limits))
        assertEquals(WizardField(WizardFieldStatus.VALID, 4_050), BolusWizardValidation.dose("4.05", limits))
    }

    @Test
    fun doseNotAlignedIsInvalidNotRounded() {
        // 不替用户取整，只回报事实
        val field = BolusWizardValidation.dose("4.03", limits)
        assertEquals(WizardFieldStatus.INVALID, field.status)
        assertEquals(WizardFieldIssue.NOT_ALIGNED, field.issue)
        assertNull(field.value)
    }

    @Test
    fun doseOutOfLimits() {
        assertEquals(WizardFieldIssue.ABOVE_MAX, BolusWizardValidation.dose("999", limits).issue)
        assertEquals(WizardFieldIssue.BELOW_MIN, BolusWizardValidation.dose("0", limits).issue)
        assertEquals(WizardFieldIssue.NOT_A_NUMBER, BolusWizardValidation.dose("abc", limits).issue)
    }
}
