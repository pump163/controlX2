package com.pumppan.controlx2.bolus.wizard

import com.pumppan.controlx2.bolus.state.CgmPrefillRange
import com.pumppan.controlx2.bolus.state.WizardConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入快照层：一次取数固定全部输入，并给出**分项**过期判定。
 *
 * 口径：
 * - 主动查询之后到达的读数才算「本次查询」；泵参数与 IOB 各自判过期，
 *   泵参数过期不连带作废 IOB。
 * - IOB 先发真实查询；失败才退回缓存，缓存窗口为 3 分钟。
 * - 近 5 分钟有输注（本机发送 / 泵历史新增），或泵真相层显示正在输注（含延展）→ 本次不扣。
 */
class BolusWizardSnapshotTest {

    private val config = WizardConfig.Default
    private val now = 1_000_000L

    private fun reading(atMillis: Long = now) = WizardPumpReading(
        params = WizardPumpParams(
            carbRatioGramsPerUnit = 6.0,
            isfMgdlPerUnit = 50,
            targetBgMgdl = 120,
            maxBolusMilliunits = 25_000,
            carbEntryEnabled = true,
            atMillis = atMillis,
        ),
        atMillis = atMillis,
    )

    @Test
    fun ready_whenCurrentQueryParamsFresh() {
        val snapshot = WizardSnapshots.build(
            cgmMgdl = 150,
            cgmAtMillis = now - 1_000,
            raw = reading(),
            nowMillis = now,
            config = config,
            queryAtMillis = now - 1_000,
            iobMilliunits = 1_500,
            iobAtMillis = now - 500,
        )
        assertTrue(snapshot.ready)
        assertNull(snapshot.unavailable)
        assertFalse(snapshot.paramsStale)
        assertTrue(snapshot.glucose.available)
        assertEquals(150, snapshot.glucose.mgdl)
        assertEquals(WizardIobSource.CURRENT_QUERY, snapshot.iob?.source)
        assertNull(snapshot.iobUnavailable)
    }

    @Test
    fun noPumpParams_blocksCalculation() {
        val snapshot = WizardSnapshots.build(150, now - 1_000, null, now, config)
        assertFalse(snapshot.ready)
        assertEquals(WizardUnavailableReason.NO_PUMP_PARAMS, snapshot.unavailable)
        assertNull(snapshot.iob)
        assertEquals(WizardIobUnavailableReason.EXPIRED_CACHE, snapshot.iobUnavailable)
    }

    @Test
    fun stalePumpParams_blocksCalcButIobSurvives() {
        val stale = now - config.snapshotStaleMillis - 1
        val snapshot = WizardSnapshots.build(
            cgmMgdl = null,
            cgmAtMillis = null,
            raw = reading(atMillis = stale),
            nowMillis = now,
            config = config,
            iobMilliunits = 1_500,
            iobAtMillis = stale,
        )

        assertFalse(snapshot.ready)
        assertTrue(snapshot.paramsStale)
        assertNull(snapshot.unavailable)
        // 泵参数过期不连带作废 IOB：IOB 处在自己的 3 分钟窗口内。
        assertEquals(1_500, snapshot.iob!!.milliunits)
        assertEquals(WizardIobSource.CACHE, snapshot.iob!!.source)
    }

    @Test
    fun pumpParamsAtThresholdStillFresh() {
        val snapshot = WizardSnapshots.build(
            cgmMgdl = null,
            cgmAtMillis = null,
            raw = reading(atMillis = now - config.snapshotStaleMillis),
            nowMillis = now,
            config = config,
        )
        assertTrue(snapshot.ready)
        assertFalse(snapshot.paramsStale)
    }

    @Test
    fun currentQueryKeepsParamsFreshBeyondShortWindow() {
        // 读数晚于查询发起时刻 → 属于本次查询，不再受 120 s 缓存时效压制。
        val snapshot = WizardSnapshots.build(
            cgmMgdl = null,
            cgmAtMillis = null,
            raw = reading(atMillis = now - 10_000),
            nowMillis = now,
            config = config,
            queryAtMillis = now - 20_000,
            iobMilliunits = 1_500,
            iobAtMillis = now - 10_000,
        )
        assertTrue(snapshot.ready)
        assertFalse(snapshot.paramsStale)
        assertEquals(WizardIobSource.CURRENT_QUERY, snapshot.iob!!.source)
    }

    @Test
    fun readingBeforeLatestQuery_isTreatedAsCache() {
        val snapshot = WizardSnapshots.build(
            cgmMgdl = null,
            cgmAtMillis = null,
            raw = reading(atMillis = now - 10_000),
            nowMillis = now,
            config = config,
            queryAtMillis = now - 5_000,
            iobMilliunits = 1_500,
            iobAtMillis = now - 10_000,
        )
        assertEquals(WizardIobSource.CACHE, snapshot.iob!!.source)
    }

    @Test
    fun iobWindowIsThreeMinutes() {
        assertEquals(180_000L, config.iobStaleAfterMs)
    }

    @Test
    fun cachedIobWithinWindowIsUsable() {
        val withinWindow = now - config.iobStaleAfterMs + 1
        val snapshot = WizardSnapshots.build(
            cgmMgdl = null,
            cgmAtMillis = null,
            raw = reading(atMillis = withinWindow),
            nowMillis = now,
            config = config,
            iobMilliunits = 1_500,
            iobAtMillis = withinWindow,
        )
        assertEquals(1_500, snapshot.iob!!.milliunits)
        assertEquals(WizardIobSource.CACHE, snapshot.iob!!.source)
        assertNull(snapshot.iobUnavailable)
    }

    @Test
    fun cachedIobBeyondWindowIsUnavailable() {
        val expired = now - config.iobStaleAfterMs - 1
        val snapshot = WizardSnapshots.build(
            cgmMgdl = null,
            cgmAtMillis = null,
            raw = reading(atMillis = expired),
            nowMillis = now,
            config = config,
            iobMilliunits = 1_500,
            iobAtMillis = expired,
        )
        assertNull(snapshot.iob)
        assertEquals(WizardIobUnavailableReason.EXPIRED_CACHE, snapshot.iobUnavailable)
    }

    @Test
    fun currentQueryIobIsTrustedEvenIfOldArrivalTimestamp() {
        // 到达时刻不早于查询发起时刻 → 本次查询，直接采信，不再套缓存窗口。
        val snapshot = WizardSnapshots.build(
            cgmMgdl = null,
            cgmAtMillis = null,
            raw = reading(),
            nowMillis = now,
            config = config,
            queryAtMillis = now - 20_000,
            iobMilliunits = 1_500,
            iobAtMillis = now - 10_000,
        )
        assertEquals(WizardIobSource.CURRENT_QUERY, snapshot.iob!!.source)
        assertNull(snapshot.iobUnavailable)
    }

    @Test
    fun noIobAtAll_isUnavailable() {
        val snapshot = WizardSnapshots.build(null, null, reading(), now, config)
        assertNull(snapshot.iob)
        assertEquals(WizardIobUnavailableReason.EXPIRED_CACHE, snapshot.iobUnavailable)
    }

    @Test
    fun recentInfusionVetoesIob_withoutDeduction() {
        val snapshot = WizardSnapshots.build(
            cgmMgdl = null,
            cgmAtMillis = null,
            raw = reading(),
            nowMillis = now,
            config = config,
            queryAtMillis = now,
            iobMilliunits = 1_500,
            iobAtMillis = now,
            lastInfusionAtMillis = now - 60_000,
        )
        assertNull(snapshot.iob)
        assertEquals(WizardIobUnavailableReason.INFUSION_ACTIVITY, snapshot.iobUnavailable)
    }

    @Test
    fun infusionAtVetoBoundaryStillVetoes() {
        val snapshot = WizardSnapshots.build(
            cgmMgdl = null,
            cgmAtMillis = null,
            raw = reading(),
            nowMillis = now,
            config = config,
            queryAtMillis = now,
            iobMilliunits = 1_500,
            iobAtMillis = now,
            lastInfusionAtMillis = now - config.recentInfusionVetoMillis,
        )
        assertNull(snapshot.iob)
        assertEquals(WizardIobUnavailableReason.INFUSION_ACTIVITY, snapshot.iobUnavailable)
    }

    @Test
    fun oldInfusionDoesNotVeto() {
        val snapshot = WizardSnapshots.build(
            cgmMgdl = null,
            cgmAtMillis = null,
            raw = reading(),
            nowMillis = now,
            config = config,
            queryAtMillis = now,
            iobMilliunits = 1_500,
            iobAtMillis = now,
            lastInfusionAtMillis = now - config.recentInfusionVetoMillis - 1,
        )
        assertEquals(1_500, snapshot.iob!!.milliunits)
        assertNull(snapshot.iobUnavailable)
    }

    @Test
    fun deliveringBolusVetoesIob() {
        val snapshot = WizardSnapshots.build(
            cgmMgdl = null,
            cgmAtMillis = null,
            raw = reading(),
            nowMillis = now,
            config = config,
            queryAtMillis = now,
            iobMilliunits = 1_500,
            iobAtMillis = now,
            bolusDelivering = true,
        )
        assertNull(snapshot.iob)
        assertEquals(WizardIobUnavailableReason.INFUSION_ACTIVITY, snapshot.iobUnavailable)
    }

    @Test
    fun extendedBolusVetoesIob() {
        // 延展期间 IOB 实时值最多滞后约 1 U，比时间戳过期更严重：本次不扣。
        val snapshot = WizardSnapshots.build(
            cgmMgdl = null,
            cgmAtMillis = null,
            raw = reading(),
            nowMillis = now,
            config = config,
            queryAtMillis = now,
            iobMilliunits = 1_500,
            iobAtMillis = now,
            extendedBolusActive = true,
        )
        assertNull(snapshot.iob)
        assertEquals(WizardIobUnavailableReason.INFUSION_ACTIVITY, snapshot.iobUnavailable)
    }

    @Test
    fun invalidPumpParams_blocksCalculation() {
        val broken = reading().copy(params = reading().params.copy(carbRatioGramsPerUnit = 0.0))
        val snapshot = WizardSnapshots.build(null, null, broken, now, config)
        assertEquals(WizardUnavailableReason.PUMP_PARAMS_INVALID, snapshot.unavailable)

        assertEquals(
            WizardUnavailableReason.PUMP_PARAMS_INVALID,
            WizardSnapshots.build(null, null, reading().copy(params = reading().params.copy(isfMgdlPerUnit = 0)), now, config).unavailable,
        )
        assertEquals(
            WizardUnavailableReason.PUMP_PARAMS_INVALID,
            WizardSnapshots.build(null, null, reading().copy(params = reading().params.copy(targetBgMgdl = 0)), now, config).unavailable,
        )
    }

    @Test
    fun disabledConfig_blocksCalculation() {
        val disabled = config.copy(enabled = false)
        val snapshot = WizardSnapshots.build(150, now, reading(), now, disabled)
        assertEquals(WizardUnavailableReason.DISABLED, snapshot.unavailable)
    }

    @Test
    fun staleCgm_isUnavailable() {
        val glucose = WizardSnapshots.glucoseOf(
            mgdl = 150,
            atMillis = now - config.cgmStaleMillis - 1,
            nowMillis = now,
            config = config,
        )
        assertNull(glucose.mgdl)
        assertTrue(glucose.stale)
        assertFalse(glucose.available)
    }

    @Test
    fun cgmWithoutReceiptTime_isUnavailable() {
        // 没有本地收到时间就无法判时效，按读不到处理
        assertNull(WizardSnapshots.glucoseOf(150, null, now, config).mgdl)
    }

    @Test
    fun zeroOrNegativeCgm_isNoReading() {
        assertNull(WizardSnapshots.glucoseOf(0, now, now, config).mgdl)
        assertNull(WizardSnapshots.glucoseOf(-5, now, now, config).mgdl)
    }

    @Test
    fun cgmOutsidePrefillRange_isNotFilled() {
        // 默认保守交集 40–400：越界不预填，避免填入该传感器不可能给出的值
        assertNull(WizardSnapshots.glucoseOf(39, now, now, config).mgdl)
        assertNull(WizardSnapshots.glucoseOf(401, now, now, config).mgdl)
        assertEquals(40, WizardSnapshots.glucoseOf(40, now, now, config).mgdl)
        assertEquals(400, WizardSnapshots.glucoseOf(400, now, now, config).mgdl)
    }

    @Test
    fun prefillRange_followsSensorModel() {
        val perSensor = config.copy(
            cgmPrefillBySensor = mapOf(
                "DEXCOM_G6" to CgmPrefillRange(40, 400),
                "LIBRE" to CgmPrefillRange(40, 500),
            ),
        )
        assertEquals(450, WizardSnapshots.glucoseOf(450, now, now, perSensor, "LIBRE").mgdl)
        assertNull(WizardSnapshots.glucoseOf(450, now, now, perSensor, "DEXCOM_G6").mgdl)
        // 未知 / 未识别型号取保守交集 40–400
        assertNull(WizardSnapshots.glucoseOf(450, now, now, perSensor, "UNKNOWN_SENSOR").mgdl)
        assertEquals(400, WizardSnapshots.glucoseOf(400, now, now, perSensor, null).mgdl)
    }

    @Test
    fun readingFromPump_doesNotCarryIob() {
        // IOB 必须单独通过 ControlIQIOBRequest 取，不再从 BolusCalcDataSnapshot 报文里拿。
        val reading = WizardSnapshots.readingFromPump(
            carbRatioPerMille = 6_000,
            isfMgdlPerUnit = 50,
            targetBgMgdl = 120,
            maxBolusMilliunits = 25_000,
            carbEntryEnabled = true,
            atMillis = now,
        )
        assertEquals(6.0, reading.params.carbRatioGramsPerUnit, 0.0001)
        assertEquals(now, reading.atMillis)
        assertEquals(WizardDataOrigin.PUMP, reading.origin)
    }
}
