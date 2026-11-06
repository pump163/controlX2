package com.pumppan.controlx2.bolus.preset

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1 → v2 换算的单测：旧库是「总量 + 立即占比 + 时长（秒）」，
 * 新库只存绝对值「立即段 / 延后段 / 时长（分钟）」。
 *
 * 注：Room 迁移的 SQL 执行本身需要 instrumentation / Robolectric，
 * 这里覆盖迁移里唯一的业务换算 [convertLegacyPreset]（迁移实现直接复用它）。
 */
class BolusPresetMigrationTest {

    @Test
    fun nonExtended_movesEverythingIntoImmediate() {
        val converted = convertLegacyPreset(
            totalMilliunits = 1000,
            extendedEnabled = false,
            nowPercent = 100,
            extendedSeconds = 0,
            incrementMilliunits = 50,
        )
        assertEquals(1000, converted.immediateMilliunits)
        assertEquals(0, converted.laterMilliunits)
        assertEquals(0, converted.durationMinutes)
    }

    @Test
    fun extended_usesImmediatePercentForImmediateSegment() {
        // nowPercent 是立即占比，延后段必须是 40% 而不是 60%。
        val converted = convertLegacyPreset(1000, true, 60, 7200, 50)
        assertEquals(600, converted.immediateMilliunits)
        assertEquals(400, converted.laterMilliunits)
        assertEquals(120, converted.durationMinutes)
    }

    @Test
    fun extended_roundsLaterByX2Increment() {
        val converted = convertLegacyPreset(1000, true, 33, 3600, 50)
        assertEquals(350, converted.immediateMilliunits)
        assertEquals(650, converted.laterMilliunits)
    }

    @Test
    fun extended_roundsLaterByMobiIncrement() {
        val converted = convertLegacyPreset(1000, true, 33, 3600, 10)
        assertEquals(330, converted.immediateMilliunits)
        assertEquals(670, converted.laterMilliunits)
    }

    @Test
    fun extended_percentEdgesStayWithinTotal() {
        val allLater = convertLegacyPreset(1000, true, 0, 3600, 50)
        assertEquals(0, allLater.immediateMilliunits)
        assertEquals(1000, allLater.laterMilliunits)

        val allImmediate = convertLegacyPreset(1000, true, 100, 3600, 50)
        assertEquals(1000, allImmediate.immediateMilliunits)
        assertEquals(0, allImmediate.laterMilliunits)

        // 越界百分比按 0..100 夹紧。
        val over = convertLegacyPreset(1000, true, 150, 3600, 50)
        assertEquals(0, over.laterMilliunits)
        val under = convertLegacyPreset(1000, true, -20, 3600, 50)
        assertEquals(1000, under.laterMilliunits)
    }

    @Test
    fun emptyAndZeroDoseRowsStayEmpty() {
        val converted = convertLegacyPreset(0, false, 100, 0, 50)
        assertEquals(0, converted.immediateMilliunits)
        assertEquals(0, converted.laterMilliunits)
        assertEquals(0, converted.durationMinutes)
    }
}
