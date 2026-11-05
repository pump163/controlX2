package com.pumppan.controlx2.bolus.preset

import com.pumppan.controlx2.bolus.state.BolusFlowStep
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.BolusShape
import com.pumppan.controlx2.bolus.state.BolusSource
import org.junit.Assert.assertEquals
import org.junit.Test

class BolusPresetTest {

    private val x2Limits = BolusLimits.forModel(com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel.TSLIM_X2)

    private val normalPreset = BolusPreset(
        id = 1L,
        name = "早餐",
        sortOrder = 0,
        totalMilliunits = 1000,
        extendedEnabled = false,
        nowPercent = 100,
        extendedSeconds = 0,
    )

    private val extendedPreset = BolusPreset(
        id = 2L,
        name = "晚餐",
        sortOrder = 1,
        totalMilliunits = 1000,
        extendedEnabled = true,
        nowPercent = 70,
        extendedSeconds = 3600,
    )

    @Test
    fun normalPreset_prefillsShapeStepAsDraft() {
        val state = normalPreset.prefilledState(x2Limits)
        assertEquals(BolusFlowStep.SHAPE, state.step)
        assertEquals(BolusSource.PRESET, state.source)
        assertEquals(BolusShape.NORMAL, state.shape)
        assertEquals(1000, state.nowMilliunits)
        assertEquals(0, state.laterMilliunits)
        assertEquals(1000, state.totalMilliunits)
    }

    @Test
    fun extendedPreset_prefillsSplitAndDuration() {
        val state = extendedPreset.prefilledState(x2Limits)
        assertEquals(BolusShape.DUAL, state.shape)
        assertEquals(700, state.nowMilliunits)
        assertEquals(300, state.laterMilliunits)
        assertEquals(1000, state.totalMilliunits)
        assertEquals(70, state.percent)
        assertEquals(3600, state.extendedSeconds)
    }

    @Test
    fun specFromDraft_dropsExtendedFieldsForNormalShape() {
        val state = normalPreset.prefilledState(x2Limits)
        assertEquals(BolusPresetSpec(1000, false, 100, 0), BolusPresetSpec.from(state))
    }

    @Test
    fun specFromDraft_keepsExtendedFields() {
        val state = extendedPreset.prefilledState(x2Limits)
        assertEquals(extendedPreset.spec(), BolusPresetSpec.from(state))
    }
}
