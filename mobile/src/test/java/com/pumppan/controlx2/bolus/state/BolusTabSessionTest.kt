package com.pumppan.controlx2.bolus.state

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BolusTabSessionTest {

    @Test
    fun default_isBolusTab_withoutDraft() {
        val session = BolusTabSession()
        assertEquals(BolusTab.BOLUS, session.tab)
        assertNull(session.origin)
        assertNull(session.draft)
        assertFalse(session.flowOpen)
    }

    @Test
    fun select_preservesDraftAndOrigin_whenSwitchingTabs() {
        val session = BolusTabSession()
            .startFlow(BolusTab.BOLUS)
            .updateDraft(BolusFlowState(step = BolusFlowStep.CONFIRM, nowMilliunits = 400))

        val onPresets = session.select(BolusTab.PRESETS)
        assertEquals(BolusTab.PRESETS, onPresets.tab)
        assertEquals(BolusTab.BOLUS, onPresets.origin)
        assertEquals(BolusFlowState(step = BolusFlowStep.CONFIRM, nowMilliunits = 400), onPresets.draft)
        assertTrue(onPresets.flowOpen)

        // 切回「大剂量」仍是同一份草稿（相位、数值都没丢）。
        val back = onPresets.select(BolusTab.BOLUS)
        assertEquals(session.draft, back.draft)
        assertEquals(BolusFlowStep.CONFIRM, back.draft?.step)
    }

    @Test
    fun startFlow_opensAtInputStep_andRecordsOrigin() {
        val session = BolusTabSession().startFlow(BolusTab.BOLUS)
        assertEquals(BolusTab.BOLUS, session.tab)
        assertEquals(BolusTab.BOLUS, session.origin)
        assertEquals(BolusFlowStep.SHAPE, session.draft?.step)
    }

    @Test
    fun startFlow_reusesExistingDraft_neverCreatesSecondDraft() {
        val first = BolusTabSession().startFlow(BolusTab.BOLUS)
        val firstDraft = first.draft
        assertNotNull(firstDraft)

        // 重复点击入口 / 从另一个标签再进：必须复用同一份草稿，不能新建第二份。
        val again = first.startFlow(BolusTab.PRESETS)
        assertSame(firstDraft, again.draft)
        assertEquals(BolusTab.BOLUS, again.tab)
    }

    @Test
    fun applyPreset_handsOffToBolusConfirm_withPresetOrigin() {
        val presetState = BolusFlowState(
            step = BolusFlowStep.CONFIRM,
            source = BolusSource.PRESET,
            nowMilliunits = 400,
        )
        val session = BolusTabSession()
            .select(BolusTab.PRESETS)
            .applyPreset(presetState)

        assertEquals(BolusTab.BOLUS, session.tab)
        assertEquals(BolusTab.PRESETS, session.origin)
        assertEquals(BolusFlowStep.CONFIRM, session.draft?.step)
        assertEquals(400, session.draft?.nowMilliunits)
        assertEquals(BolusSource.PRESET, session.draft?.source)
    }

    @Test
    fun exitFlow_returnsToOriginTab_andDestroysDraft() {
        val session = BolusTabSession()
            .select(BolusTab.PRESETS)
            .applyPreset(BolusFlowState(step = BolusFlowStep.CONFIRM, nowMilliunits = 400))

        val exited = session.exitFlow()
        assertEquals(BolusTab.PRESETS, exited.tab)
        assertNull(exited.draft)
        assertNull(exited.origin)
    }

    @Test
    fun exitFlow_fallsBackToBolusTab_whenOriginUnknown() {
        // 来源未知（首次进入 / 进程重建恢复失败）→ 回「大剂量」。
        val exited = BolusTabSession().exitFlow()
        assertEquals(BolusTab.BOLUS, exited.tab)
        assertEquals(BolusTab.BOLUS, returnTab(null))
        assertEquals(BolusTab.PRESETS, returnTab(BolusTab.PRESETS))
    }

    @Test
    fun clearDraft_keepsTabButDropsDraft() {
        val session = BolusTabSession()
            .select(BolusTab.PRESETS)
            .applyPreset(BolusFlowState(step = BolusFlowStep.RESULT))
            .clearDraft()
        assertNull(session.draft)
        assertNull(session.origin)
        assertEquals(BolusTab.BOLUS, session.tab)
    }

    @Test
    fun saver_roundTripsTabOriginAndSingleDraft() {
        val session = BolusTabSession()
            .select(BolusTab.PRESETS)
            .applyPreset(
                BolusFlowState(
                    step = BolusFlowStep.CONFIRM,
                    source = BolusSource.PRESET,
                    nowMilliunits = 120,
                    laterMilliunits = 380,
                    extendedSeconds = 5_400,
                    percent = 24,
                )
            )

        val scope = SaverScope { true }
        val saved = with(BolusTabSession.Saver) { scope.save(session) }
        assertNotNull(saved)
        val restored = BolusTabSession.Saver.restore(saved!!)
        assertEquals(session, restored)
    }

    @Test
    fun saver_restoresUnknownTabAndOriginToDefaults() {
        // 进程重建时值被破坏 → 标签回「大剂量」，来源视为未知（退出流程回大剂量）。
        val restored = BolusTabSession.Saver.restore(listOf("bogus", "bogus", emptyList<Any>()))
        assertEquals(BolusTab.BOLUS, restored?.tab)
        assertNull(restored?.origin)
        assertNull(restored?.draft)
    }
}
