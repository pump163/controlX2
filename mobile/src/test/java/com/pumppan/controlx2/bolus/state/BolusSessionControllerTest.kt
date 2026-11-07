package com.pumppan.controlx2.bolus.state

import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.helpers.Dates
import com.jwoglom.pumpx2.pump.messages.response.control.BolusPermissionResponse
import com.jwoglom.pumpx2.pump.messages.response.control.CancelBolusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusV2Response
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.LastBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.LastBolusStatusV2Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.TimeZone
import kotlin.math.abs

class BolusSessionControllerTest {

    private var now = System.currentTimeMillis()
    private val controller = BolusSessionController(
        mode = { BolusControllerMode.V2 },
        clock = { now },
    )

    // ---------- 开关 ----------

    @Test
    fun legacyMode_doesNotConsumeMessages() {
        val legacy = BolusSessionController(clock = { now })
        legacy.applyMessage(deliveringBolus(77))
        assertNull(legacy.session)
    }

    // ---------- 44 CurrentBolusStatus ----------

    @Test
    fun currentBolusIdle_marksImmediateLegDoneButKeepsSession() {
        controller.applyMessage(deliveringBolus(77))
        assertEquals(BolusDeliveryState.ACTIVE, controller.session?.state)

        // 新模型：45 归零只作为「立即段结束」证据；整次输注的判决仍等 -91 / 延展 47 归零。
        controller.applyMessage(CurrentBolusStatusResponse(0, 0, 0, 0, 0, 0))
        val session = controller.session
        assertNotNull(session)
        assertEquals(BolusDeliveryState.ACTIVE, session?.state)
        assertEquals(77, session?.bolusId)
        assertNotNull(session?.immediateLegDoneAtMillis)
    }

    @Test
    fun currentBolusWithId_isDeliveringAndActive() {
        controller.applyMessage(deliveringBolus(77))

        assertEquals(BolusDeliveryState.ACTIVE, controller.session?.state)
        assertEquals(77, controller.session?.bolusId)
        assertEquals(BolusActivity.ACTIVE, controller.holder.isAnyBolusActive(connected = true))
        assertEquals(CancelRowState.READY, controller.holder.cancelRowState(connected = true))
    }

    // ---------- 46 / -74 延展 ----------

    @Test
    fun extendedBolusV1_isExtendedRunningWithRemaining() {
        val duration = 900L
        controller.applyMessage(
            ExtendedBolusStatusResponse(1, 77, pumpTimestampAt(now), 2300, duration, 0)
        )

        assertEquals(BolusDeliveryState.ACTIVE, controller.session?.state)
        assertTrue(controller.session?.isExtendedRunning == true)
        assertEquals(77, controller.session?.bolusId)
        assertRemaining(duration)
    }

    @Test
    fun extendedBolusV2_isExtendedRunningWithRemaining() {
        val duration = 900L
        controller.applyMessage(
            ExtendedBolusStatusV2Response(1, 78, pumpTimestampAt(now), 2300, duration, 0, 0)
        )

        assertEquals(BolusDeliveryState.ACTIVE, controller.session?.state)
        assertTrue(controller.session?.isExtendedRunning == true)
        assertEquals(78, controller.session?.bolusId)
        assertRemaining(duration)
    }

    @Test
    fun extendedBolusEmpty_keepsCurrentDeliveringPhase() {
        controller.applyMessage(deliveringBolus(77))
        controller.applyMessage(ExtendedBolusStatusResponse(0, 0, 0, 0, 0, 0))

        assertEquals(BolusDeliveryState.ACTIVE, controller.session?.state)
    }

    // ---------- -94 BolusPermission ----------

    @Test
    fun permissionWithId_doesNotCreateSessionBeforeSend() {
        controller.applyMessage(BolusPermissionResponse(0, 77, 0))

        // -94 只表示「可以发」，不再建立 CONFIRMING 会话：
        // 否则流程中途退出时会留下一个没有 -98、也没有出口的等待态。
        assertNull(controller.session)
        assertEquals(BolusOutcome.Pending, controller.outcome)

        controller.applyMessage(deliveringBolus(77))
        assertEquals(BolusDeliveryState.ACTIVE, controller.session?.state)
        assertEquals(77, controller.session?.bolusId)
    }

    @Test
    fun permissionWithoutId_doesNotCreateSession() {
        controller.applyMessage(BolusPermissionResponse(0, 0, 0))

        assertNull(controller.session)
        assertEquals(BolusOutcome.Pending, controller.outcome)
    }

    // ---------- 取消终态兜底 ----------

    @Test
    fun cancelling_without95_fallsUnknownAtCancelConfirmWindow() {
        controller.applyMessage(deliveringBolus(77))
        assertEquals(BolusDeliveryState.ACTIVE, controller.session?.state)

        controller.holder.requestCancel(connected = true, now = now)
        assertEquals(BolusDeliveryState.CANCELLING, controller.session?.state)

        now += 9_999
        controller.onTick(now)
        assertEquals(BolusDeliveryState.CANCELLING, controller.session?.state)

        // -95 一直不来：超过取消确认窗口必须落终态，不能无限停在 CANCELLING。
        now += 1
        controller.onTick(now)
        assertEquals(BolusDeliveryState.UNKNOWN, controller.session?.state)
        assertTrue(controller.session?.isFinished == true)
        assertEquals(
            BolusOutcome.Failed(error = BolusError.TIMEOUT, fromCancel = true),
            controller.outcome,
        )
    }

    @Test
    fun permissionNack_failsWithNack() {
        controller.applyMessage(BolusPermissionResponse(1, 0, 1))

        // 新模型：NACK 到达时尚未建立会话快照（-98 未发出），只落结局。
        assertNull(controller.session)
        assertEquals(BolusOutcome.Failed(BolusError.NACK), controller.outcome)
    }

    // ---------- -96 CancelBolus ----------

    @Test
    fun cancelSuccess_isDoneAndCancelled() {
        controller.applyMessage(deliveringBolus(77))
        controller.applyMessage(CancelBolusResponse(0, 77, 0))

        assertEquals(BolusDeliveryState.CANCELLED, controller.session?.state)
        assertTrue(controller.session?.isFinished == true)
        assertEquals(BolusOutcome.Cancelled, controller.outcome)
    }

    @Test
    fun cancelFailure_isFailedWithNack() {
        controller.applyMessage(deliveringBolus(77))
        controller.applyMessage(CancelBolusResponse(1, 77, 2))

        assertEquals(BolusDeliveryState.UNKNOWN, controller.session?.state)
        assertTrue(controller.session?.isFinished == true)
        assertEquals(
            BolusOutcome.Failed(
                BolusError.NACK,
                statusId = 1,
                reasonId = 2,
                fromCancel = true,
            ),
            controller.outcome,
        )
    }

    // ---------- 48 LastBolusStatus ----------

    @Test
    fun lastBolusComplete_matchingId_isDoneSuccess() {
        controller.applyMessage(deliveringBolus(77))
        controller.applyMessage(LastBolusStatusV2Response(1, 77, 0, 2300, 3, 0, 0, 0, 2300))

        assertEquals(BolusDeliveryState.IDLE, controller.session?.state)
        assertTrue(controller.session?.isFinished == true)
        assertEquals(BolusOutcome.Success, controller.outcome)
    }

    @Test
    fun lastBolusOtherId_isIgnored() {
        controller.applyMessage(deliveringBolus(77))
        controller.applyMessage(LastBolusStatusV2Response(1, 78, 0, 2300, 3, 0, 0, 0, 2300))

        assertEquals(BolusDeliveryState.ACTIVE, controller.session?.state)
        assertEquals(BolusOutcome.Pending, controller.outcome)
    }

    // ---------- 结局页「关闭」 ----------

    @Test
    fun dismissFinished_clearsFinishedSessionAndResetsOutcome() {
        controller.applyMessage(deliveringBolus(77))
        controller.applyMessage(LastBolusStatusV2Response(1, 77, 0, 2300, 3, 0, 0, 0, 2300))
        assertEquals(BolusOutcome.Success, controller.outcome)

        controller.dismissFinished()

        assertNull(controller.session)
        assertEquals(BolusOutcome.Pending, controller.outcome)
    }

    @Test
    fun dismissFinished_doesNotClearActiveSession() {
        controller.applyMessage(deliveringBolus(77))

        controller.dismissFinished()

        assertEquals(BolusDeliveryState.ACTIVE, controller.session?.state)
    }

    // ---------- 未知 / 字段缺失 ----------

    @Test
    fun unknownMessage_doesNotChangeState() {
        controller.applyMessage(deliveringBolus(77))
        controller.applyMessage(UnknownMessage())

        assertEquals(BolusDeliveryState.ACTIVE, controller.session?.state)
        assertEquals(77, controller.session?.bolusId)
    }

    @Test
    fun emptyFields_doNotThrowAndKeepState() {
        controller.applyMessage(deliveringBolus(77))
        // 默认构造 = 字段全 0 的「缺字段」报文，只应被忽略
        controller.applyMessage(ExtendedBolusStatusResponse())
        controller.applyMessage(LastBolusStatusResponse(0, 0, 0, 0, 0, 0, 0, 0, byteArrayOf()))

        assertEquals(BolusDeliveryState.ACTIVE, controller.session?.state)
        assertEquals(77, controller.session?.bolusId)
    }

    // ---------- 确认阶梯（10s / 60s）与延展超时 ----------

    @Test
    fun initiateLadder_confirmingToUnconfirmedAtTenSeconds() {
        controller.markInitiateSent(bolusId = 77, hasImmediateLeg = true, now = now)
        assertEquals(BolusDeliveryState.CONFIRMING, controller.session?.state)

        now += 9_999
        controller.onTick(now)
        assertEquals(BolusDeliveryState.CONFIRMING, controller.session?.state)

        now += 1
        controller.onTick(now)
        assertEquals(BolusDeliveryState.UNCONFIRMED, controller.session?.state)
    }

    @Test
    fun initiateLadder_unconfirmedToUnknownAtSixtySeconds() {
        controller.markInitiateSent(bolusId = 77, hasImmediateLeg = true, now = now)
        now += 10_000
        controller.onTick(now)
        assertEquals(BolusDeliveryState.UNCONFIRMED, controller.session?.state)

        now += 50_000
        controller.onTick(now)
        assertEquals(BolusDeliveryState.UNKNOWN, controller.session?.state)
    }

    @Test
    fun initiateLadder_evidenceBeforeTenSeconds_flipsToActive() {
        controller.markInitiateSent(bolusId = 77, hasImmediateLeg = true, now = now)
        now += 5_000
        controller.applyMessage(deliveringBolus(77))
        assertEquals(BolusDeliveryState.ACTIVE, controller.session?.state)

        // 已确认开始后，确认阶梯不再把它退回 UNCONFIRMED / UNKNOWN。
        controller.onTick(now)
        assertEquals(BolusDeliveryState.ACTIVE, controller.session?.state)
    }

    @Test
    fun extendedEndTimeout_fallsUnknownAfterFiveMinuteGrace() {
        controller.applyMessage(
            ExtendedBolusStatusResponse(1, 77, pumpTimestampAt(now), 2300, 900, 0)
        )
        val end = controller.session!!.expectedEndAtMillis!!
        now = end + 5 * 60_000L + 1
        controller.onTick(now)

        assertEquals(BolusDeliveryState.UNKNOWN, controller.session?.state)
    }

    // ---------- helpers ----------

    private fun deliveringBolus(bolusId: Int) =
        CurrentBolusStatusResponse(1, bolusId, 0, 2300, 0, 0)

    /**
     * 构造一个「此刻」的泵时间戳：泵时间是本机墙上时间，controller 用
     * [com.jwoglom.controlx2.shared.util.pumpTimeToLocalTz] 减去时区偏移，
     * 因此这里先加上偏移再换算，保证往返后回到 `millis`。
     */
    private fun pumpTimestampAt(millis: Long): Long {
        val offsetSeconds = TimeZone.getDefault().getOffset(millis) / 1000
        return Dates.fromInstantToJan12008EpochSeconds(Instant.ofEpochMilli(millis + offsetSeconds * 1000))
    }

    private fun assertRemaining(durationSeconds: Long) {
        val end = controller.session?.expectedEndAtMillis
        assertTrue("expectedEndAtMillis 应已设置", end != null)
        val expected = now + durationSeconds * 1000
        assertTrue(
            "剩余时间应约为 ${durationSeconds}s，实际 end=$end expected=$expected",
            abs(end!! - expected) <= 1_000,
        )
    }

    private class UnknownMessage : Message() {
        override fun parse(raw: ByteArray) = Unit
    }
}
