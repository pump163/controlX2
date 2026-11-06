package com.pumppan.controlx2.bolus.state

import org.junit.Assert.assertEquals
import org.junit.Test

class BolusPanelModeTest {

    @Test
    fun fromPref_defaultsToFull_whenMissingOrUnknown() {
        // 首次进入 / 老版本升级没有这个键 → 全屏。
        assertEquals(BolusPanelMode.FULL, BolusPanelMode.fromPref(null))
        assertEquals(BolusPanelMode.FULL, BolusPanelMode.fromPref(""))
        assertEquals(BolusPanelMode.FULL, BolusPanelMode.fromPref("bogus"))
    }

    @Test
    fun fromPref_roundTripsPersistedValues() {
        BolusPanelMode.values().forEach { mode ->
            assertEquals(mode, BolusPanelMode.fromPref(mode.prefValue))
        }
    }

    @Test
    fun toggled_switchesBetweenTwoTiers() {
        assertEquals(BolusPanelMode.HALF, BolusPanelMode.FULL.toggled())
        assertEquals(BolusPanelMode.FULL, BolusPanelMode.HALF.toggled())
    }

    @Test
    fun afterDrag_switchesOnlyPastThreshold() {
        val threshold = 24f
        assertEquals(BolusPanelMode.FULL, BolusPanelMode.FULL.afterDrag(0f, threshold))
        assertEquals(BolusPanelMode.FULL, BolusPanelMode.FULL.afterDrag(10f, threshold))
        assertEquals(BolusPanelMode.HALF, BolusPanelMode.FULL.afterDrag(40f, threshold))
        assertEquals(BolusPanelMode.FULL, BolusPanelMode.HALF.afterDrag(-40f, threshold))
        assertEquals(BolusPanelMode.HALF, BolusPanelMode.HALF.afterDrag(-4f, threshold))
    }

    @Test
    fun halfFractionIsAboutSixtyPercent() {
        assertEquals(0.6f, BolusPanelMode.HALF_HEIGHT_FRACTION, 0.001f)
    }
}
