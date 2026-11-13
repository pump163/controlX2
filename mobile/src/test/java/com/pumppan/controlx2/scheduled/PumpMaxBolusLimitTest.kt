package com.pumppan.controlx2.scheduled

import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.withPumpMaxBolus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 泵侧 Max Bolus 必须成为硬上限（用户 2026-11-13：泵设 20U，档案默认 25U，
 * 20–25U 曾能通过校验、到输注时才被泵拒绝，属于不可接受的漏拦）。
 */
class PumpMaxBolusLimitTest {

    private val profile = BolusLimits(maxBolusMilliunits = 25_000, bolusIncrementMilliunits = 50)

    @Test
    fun `pump max tightens profile max`() {
        val hard = profile.withPumpMaxBolus(20_000)
        assertEquals(20_000, hard.maxBolusMilliunits)
    }

    @Test
    fun `missing pump response falls back to profile`() {
        assertEquals(25_000, profile.withPumpMaxBolus(null).maxBolusMilliunits)
        assertEquals(25_000, profile.withPumpMaxBolus(0).maxBolusMilliunits)
    }

    @Test
    fun `21U rejected against 20U pump max`() {
        val hard = profile.withPumpMaxBolus(20_000)
        val plan = ScheduledPlan(
            firstAtMillis = NOW + 3_600_000,
            firstDoseMilliunits = 21_000L,
            links = listOf(
                ScheduledBolusChain.Link(doseMilliunits = 500L, delayAfterPreviousMs = 300_000L),
                ScheduledBolusChain.Link(doseMilliunits = 500L, delayAfterPreviousMs = 300_000L),
            ),
        )
        val issues = plan.validate(ScheduledBolusConfig(), hard, now = NOW)
        assertTrue(issues.toString(), issues.any { it is PlanIssue.DoseOutOfRange && it.step == 0 })
    }

    @Test
    fun `20U exactly allowed against 20U pump max`() {
        val hard = profile.withPumpMaxBolus(20_000)
        val plan = ScheduledPlan(
            firstAtMillis = NOW + 3_600_000,
            firstDoseMilliunits = 20_000L,
            links = listOf(
                ScheduledBolusChain.Link(doseMilliunits = 500L, delayAfterPreviousMs = 300_000L),
                ScheduledBolusChain.Link(doseMilliunits = 500L, delayAfterPreviousMs = 300_000L),
            ),
        )
        val issues = plan.validate(ScheduledBolusConfig(), hard, now = NOW)
        assertTrue(issues.toString(), issues.none { it is PlanIssue.DoseOutOfRange })
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
