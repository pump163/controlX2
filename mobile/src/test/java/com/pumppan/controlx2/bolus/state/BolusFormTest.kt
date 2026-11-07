package com.pumppan.controlx2.bolus.state

import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BolusFormTest {

    /** 型号分档：X2=50、Mobi=50（暂按 0.05，未真机核实）、未知=25、老泵=10。 */
    private val pumpTiers = listOf(
        "X2(50)" to BolusLimits.forModel(KnownDeviceModel.TSLIM_X2),
        "Mobi(50)" to BolusLimits.forModel(KnownDeviceModel.MOBI),
        "unknown(25)" to BolusLimits.forModel(null),
        "old-pump(10)" to BolusLimits(bolusIncrementMilliunits = 10),
    )

    @Test
    fun splitByPercent_roundsToIncrement() {
        assertEquals(BolusSplit(200, 200), BolusForm.splitByPercent(400, 50))
        assertEquals(BolusSplit(225, 250), BolusForm.splitByPercent(475, 50))
        assertEquals(BolusSplit(300, 100), BolusForm.splitByPercent(400, 75))
    }

    @Test
    fun splitByPercent_edges() {
        assertEquals(BolusSplit(0, 400), BolusForm.splitByPercent(400, 0))
        assertEquals(BolusSplit(400, 0), BolusForm.splitByPercent(400, 100))
    }

    @Test
    fun percentOf_isTheInverseSideOfSplit() {
        // 绝对值 → 派生百分比（只显示用）。
        assertEquals(100, BolusForm.percentOf(400, 400))
        assertEquals(50, BolusForm.percentOf(200, 400))
        assertEquals(70, BolusForm.percentOf(700, 1000))
        assertEquals(100, BolusForm.percentOf(0, 0))
        // 非整数比例就近取整。
        assertEquals(33, BolusForm.percentOf(330, 1000))
        assertEquals(67, BolusForm.percentOf(670, 1000))
    }

    @Test
    fun percentRoundTrip_approximatesWithinIncrement() {
        // 百分比 → 绝对值（步进量化）→ 派生百分比，允许一个步进带来的取整偏差。
        for (total in listOf(1_000, 10_000)) {
            for (percent in 0..100) {
                val split = BolusForm.splitByPercent(total, percent)
                val derived = BolusForm.percentOf(split.nowMilliunits, total)
                assertTrue("percent=$percent derived=$derived", kotlin.math.abs(derived - percent) <= 3)
            }
        }
    }

    @Test
    fun splitByPercent_alwaysOnIncrementGrid() {
        val increment = BolusLimits.BOLUS_INCREMENT_MILLIUNITS
        for (total in listOf(50, 100, 400, 1_000, 24_975, 25_000)) {
            for (percent in 0..100) {
                val split = BolusForm.splitByPercent(total, percent)
                assertEquals(0, split.nowMilliunits % increment)
                assertEquals(0, split.laterMilliunits % increment)
                assertEquals(total, split.nowMilliunits + split.laterMilliunits)
            }
        }
    }

    @Test
    fun quantizeToIncrement_parameterizedByPumpStep() {
        for (increment in listOf(10, 25, 50)) {
            for (raw in listOf(13, 38, 400)) {
                val quantized = BolusForm.quantizeToIncrement(raw, increment)
                assertEquals(0, quantized % increment)
                assertTrue(kotlin.math.abs(quantized - raw) <= increment / 2)
            }
        }
        assertEquals(10, BolusForm.quantizeToIncrement(13, 10))
        assertEquals(25, BolusForm.quantizeToIncrement(13, 25))
        assertEquals(50, BolusForm.quantizeToIncrement(38, 50))
    }

    @Test
    fun splitByPercent_parameterizedIncrement() {
        for (increment in listOf(10, 25, 50)) {
            val totals = listOf(increment * 4, increment * 10, increment * 40)
            for (total in totals) {
                for (percent in listOf(0, 25, 50, 75, 100)) {
                    val split = BolusForm.splitByPercent(total, percent, increment)
                    assertEquals(0, split.nowMilliunits % increment)
                    assertEquals(0, split.laterMilliunits % increment)
                    assertEquals(total, split.nowMilliunits + split.laterMilliunits)
                }
            }
        }
    }

    @Test
    fun incrementFor_x2MobiAndUnknown() {
        assertEquals(50, BolusLimits.incrementFor(KnownDeviceModel.TSLIM_X2))
        // Mobi 未真机核实，暂按 0.05U（50）；若核实为 0.025U 只改配置 models.MOBI。
        assertEquals(50, BolusLimits.incrementFor(KnownDeviceModel.MOBI))
        // 未知型号走 default 25：25 网格是 50 的超集，不误拦现役机型的合法剂量。
        assertEquals(25, BolusLimits.incrementFor(null))
        assertEquals(25, BolusLimits.forModel(null).bolusIncrementMilliunits)
    }

    @Test
    fun splitByPercent_x2Grid_usesFiftyMilliunits() {
        // X2 档（50）：延后段先 half-up 到 50 网格，立即段取总量减延后段。
        assertEquals(BolusSplit(300, 100), BolusForm.splitByPercent(400, 70, 50))
        assertEquals(BolusSplit(200, 250), BolusForm.splitByPercent(450, 50, 50))
        assertEquals(BolusSplit(300, 150), BolusForm.splitByPercent(450, 70, 50))

        for (total in listOf(400, 500, 24_950, 25_000)) {
            for (percent in 0..100) {
                val split = BolusForm.splitByPercent(total, percent, 50)
                assertEquals(0, split.nowMilliunits % 50)
                assertEquals(0, split.laterMilliunits % 50)
                assertEquals(total, split.nowMilliunits + split.laterMilliunits)
            }
        }
    }

    @Test
    fun validate_x2AcceptsFiftyGridAndRejectsOtherValues() {
        val x2 = BolusLimits.forModel(KnownDeviceModel.TSLIM_X2)
        assertTrue(BolusForm.isValid(BolusDraft(nowMilliunits = 400), ciqEnabled = false, limits = x2))
        assertTrue(BolusForm.isValid(BolusDraft(nowMilliunits = 25_000), ciqEnabled = false, limits = x2))
        assertEquals(
            listOf(BolusValidationError.INCREMENT_NOT_ALIGNED),
            BolusForm.validate(BolusDraft(nowMilliunits = 425), ciqEnabled = false, limits = x2),
        )
        assertEquals(
            listOf(BolusValidationError.INCREMENT_NOT_ALIGNED),
            BolusForm.validate(BolusDraft(nowMilliunits = 24_975), ciqEnabled = false, limits = x2),
        )
    }

    @Test
    fun validate_boundsAndGrid_perPumpTier() {
        for ((name, limits) in pumpTiers) {
            val inc = limits.bolusIncrementMilliunits
            assertTrue("$name: 0.05U 有效", BolusForm.isValid(BolusDraft(nowMilliunits = 50), false, limits))
            assertTrue("$name: 25U 有效", BolusForm.isValid(BolusDraft(nowMilliunits = 25_000), false, limits))
            assertTrue(
                "$name: 上限下一格有效",
                BolusForm.isValid(BolusDraft(nowMilliunits = 25_000 - inc), false, limits),
            )
            assertEquals(
                "$name: 超上限",
                listOf(BolusValidationError.AMOUNT_ABOVE_MAXIMUM),
                BolusForm.validate(BolusDraft(nowMilliunits = 25_000 + inc), false, limits),
            )
            assertEquals(
                "$name: 非步进网格",
                listOf(BolusValidationError.INCREMENT_NOT_ALIGNED),
                BolusForm.validate(BolusDraft(nowMilliunits = 50 + inc / 2), false, limits),
            )
        }
    }

    @Test
    fun validate_respectsConfiguredIncrement() {
        val limits = BolusLimits(bolusIncrementMilliunits = 10)
        assertTrue(BolusForm.isValid(BolusDraft(nowMilliunits = 50), ciqEnabled = false, limits = limits))
        assertEquals(
            listOf(BolusValidationError.INCREMENT_NOT_ALIGNED),
            BolusForm.validate(BolusDraft(nowMilliunits = 55), ciqEnabled = false, limits = limits),
        )
    }

    @Test
    fun quantizeToIncrement_halfUp() {
        assertEquals(0, BolusForm.quantizeToIncrement(0))
        assertEquals(25, BolusForm.quantizeToIncrement(13))
        assertEquals(25, BolusForm.quantizeToIncrement(25))
        assertEquals(50, BolusForm.quantizeToIncrement(38))
        assertEquals(400, BolusForm.quantizeToIncrement(400))
    }

    @Test
    fun validate_acceptsMinimumAndRejectsBelow() {
        val minimum = BolusDraft(nowMilliunits = 50)
        assertTrue(BolusForm.isValid(minimum, ciqEnabled = false))

        val below = BolusDraft(nowMilliunits = 25)
        assertEquals(
            listOf(BolusValidationError.AMOUNT_BELOW_MINIMUM),
            BolusForm.validate(below, ciqEnabled = false),
        )
    }

    @Test
    fun validate_acceptsMaximumAndRejectsAbove() {
        val maximum = BolusDraft(nowMilliunits = 25_000)
        assertTrue(BolusForm.isValid(maximum, ciqEnabled = false))
        assertTrue(BolusForm.isValid(BolusDraft(nowMilliunits = 24_975), ciqEnabled = false))

        val above = BolusDraft(nowMilliunits = 25_025)
        assertEquals(
            listOf(BolusValidationError.AMOUNT_ABOVE_MAXIMUM),
            BolusForm.validate(above, ciqEnabled = false),
        )
    }

    @Test
    fun validate_rejectsOffIncrementGrid() {
        val draft = BolusDraft(nowMilliunits = 401)
        assertEquals(
            listOf(BolusValidationError.INCREMENT_NOT_ALIGNED),
            BolusForm.validate(draft, ciqEnabled = false),
        )
    }

    @Test
    fun validate_negativeAmount() {
        val draft = BolusDraft(nowMilliunits = -1)
        assertEquals(
            listOf(BolusValidationError.NEGATIVE_AMOUNT),
            BolusForm.validate(draft, ciqEnabled = false),
        )
    }

    @Test
    fun validate_extendedTotalBoundary() {
        // Mobi 口径：立即段可为 0，延展段下限 0.4U 仍生效。
        val mobiLike = BolusLimits.Default.copy(minImmediateMilliunits = 0)
        val minimum = BolusDraft(
            nowMilliunits = 0,
            laterMilliunits = 400,
            extendedSeconds = 3_600,
            shape = BolusShape.DUAL,
        )
        assertTrue(BolusForm.isValid(minimum, ciqEnabled = false, limits = mobiLike))

        val below = minimum.copy(laterMilliunits = 375)
        assertEquals(
            listOf(BolusValidationError.EXTENDED_TOTAL_BELOW_MINIMUM),
            BolusForm.validate(below, ciqEnabled = false, limits = mobiLike),
        )
    }

    @Test
    fun validate_zeroImmediateAccepted() {
        // Mobi 0:100 —— 立即 0、全部延后；库内用例接受 totalVolume=0（未真机验证）。
        val mobiLike = BolusLimits.Default.copy(minImmediateMilliunits = 0)
        val draft = BolusDraft(
            nowMilliunits = 0,
            laterMilliunits = 400,
            extendedSeconds = 7_200,
            shape = BolusShape.DUAL,
        )
        assertTrue(BolusForm.isValid(draft, ciqEnabled = null, limits = mobiLike))
        assertFalse(BolusForm.isImmediatePartUnverified(draft, mobiLike))
    }

    @Test
    fun validate_immediateBelowModelMinimumRejected() {
        // X2 口径（minImmediate=50）：立即段 0 / 25 均低于下限，硬拦。
        val x2Like = BolusLimits.Default
        val zero = BolusDraft(
            nowMilliunits = 0,
            laterMilliunits = 400,
            extendedSeconds = 3_600,
            shape = BolusShape.DUAL,
        )
        assertTrue(
            BolusForm.validate(zero, ciqEnabled = false, limits = x2Like)
                .contains(BolusValidationError.IMMEDIATE_BELOW_MINIMUM),
        )

        val small = zero.copy(nowMilliunits = 25)
        assertTrue(
            BolusForm.validate(small, ciqEnabled = false, limits = x2Like)
                .contains(BolusValidationError.IMMEDIATE_BELOW_MINIMUM),
        )
        assertTrue(BolusForm.isImmediatePartUnverified(small, x2Like))
    }

    @Test
    fun validate_squareLaterRequired() {
        val draft = BolusDraft(
            nowMilliunits = 400,
            laterMilliunits = 0,
            extendedSeconds = 3_600,
            shape = BolusShape.SQUARE,
        )
        assertTrue(BolusForm.validate(draft, ciqEnabled = false).contains(BolusValidationError.LATER_REQUIRED))
    }

    @Test
    fun validate_durationGating_ciq() {
        val draft = BolusDraft(
            nowMilliunits = 100,
            laterMilliunits = 300,
            extendedSeconds = 7_201,
            shape = BolusShape.DUAL,
        )
        assertTrue(BolusForm.validate(draft, ciqEnabled = true).contains(BolusValidationError.DURATION_OUT_OF_RANGE))
        assertFalse(BolusForm.validate(draft, ciqEnabled = false).contains(BolusValidationError.DURATION_OUT_OF_RANGE))
    }

    @Test
    fun validate_durationGating_nonCiqUpperBound() {
        val draft = BolusDraft(
            nowMilliunits = 100,
            laterMilliunits = 300,
            extendedSeconds = 28_801,
            shape = BolusShape.SQUARE,
        )
        assertTrue(BolusForm.validate(draft, ciqEnabled = false).contains(BolusValidationError.DURATION_OUT_OF_RANGE))
    }

    @Test
    fun validate_normalShapeInconsistent() {
        assertTrue(
            BolusForm.validate(BolusDraft(nowMilliunits = 100, laterMilliunits = 50), ciqEnabled = false)
                .contains(BolusValidationError.SHAPE_INCONSISTENT),
        )
        assertTrue(
            BolusForm.validate(BolusDraft(nowMilliunits = 100, extendedSeconds = 60), ciqEnabled = false)
                .contains(BolusValidationError.SHAPE_INCONSISTENT),
        )
    }

    @Test
    fun maxExtendedSeconds_byCiqState() {
        assertEquals(7_200, BolusForm.maxExtendedSeconds(ciqEnabled = true))
        assertEquals(28_800, BolusForm.maxExtendedSeconds(ciqEnabled = false))
        assertEquals(7_200, BolusForm.maxExtendedSeconds(ciqEnabled = null))
    }

    @Test
    fun capabilities_x2DeclaresRemoteExtendedUnsupported() {
        val profile = PumpCapabilities.forModel(KnownDeviceModel.TSLIM_X2)
        assertFalse(profile.remoteExtendedBolusStart)
        assertTrue(profile.extendedBolusStatusRead)
        assertTrue(profile.extendedBolusCancel)
        assertEquals("unverified", profile.permissionRelease.status)

        val x2 = BolusLimits.forModel(KnownDeviceModel.TSLIM_X2)
        assertFalse(x2.remoteExtendedBolusStart)
        val dual = BolusDraft(
            nowMilliunits = 300,
            laterMilliunits = 700,
            extendedSeconds = 3_600,
            shape = BolusShape.DUAL,
        )
        assertEquals(
            listOf(BolusValidationError.REMOTE_EXTENDED_NOT_SUPPORTED),
            BolusForm.validate(dual, ciqEnabled = false, limits = x2),
        )
        // 兜底 / Mobi 仍允许扩展，避免配置缺失时误伤其它型号。
        assertTrue(BolusForm.isValid(dual, ciqEnabled = false, limits = BolusLimits.Default))
        assertTrue(BolusLimits.forModel(KnownDeviceModel.MOBI).remoteExtendedBolusStart)
    }

    @Test
    fun capabilities_x2ThresholdsAndUnits() {
        val profile = PumpCapabilities.forModel(KnownDeviceModel.TSLIM_X2)
        assertEquals(50, profile.bolusIncrementMilliunits)
        assertEquals(50, profile.minImmediateMilliunits)
        assertEquals(400, profile.clientMinExtendedCheckMilliunits)
        assertEquals(900, profile.minExtendedSeconds)
        assertEquals("请到泵本体操作", profile.unsupportedHint)
        assertEquals(25_000, profile.maxBolusMilliunits)
        assertEquals(7_200, profile.maxExtendedSecondsCiq)
        assertEquals(28_800, profile.maxExtendedSecondsNonCiq)
        assertEquals("seconds", profile.wireDurationUnit)
        assertEquals("minutes", profile.historyExtendedDurationUnit)
        assertEquals(1, profile.historyBolusTypeBitmask["NOW"])
        assertEquals(2, profile.historyBolusTypeBitmask["LATER"])
        assertEquals(16, profile.historyBolusTypeBitmask["CARB"])
    }
}
