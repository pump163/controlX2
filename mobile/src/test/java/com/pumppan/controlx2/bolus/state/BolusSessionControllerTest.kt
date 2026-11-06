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
    fun currentBolusIdle_clearsSession() {
        controller.applyMessage(deliveringBolus(77))
        assertEquals(SessionPhase.DELIVERING, controller.session?.phase)

        controller.applyMessage(CurrentBolusStatusResponse(0, 0, 0, 0, 0, 0))
        assertNull(controller.session)
    }

    @Test
    fun currentBolusWithId_isDeliveringAndActive() {
        controller.applyMessage(deliveringBolus(77))

        assertEquals(SessionPhase.DELIVERING, controller.session?.phase)
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

        assertEquals(SessionPhase.EXTENDED_RUNNING, controller.session?.phase)
        assertEquals(77, controller.session?.bolusId)
        assertRemaining(duration)
    }

    @Test
    fun extendedBolusV2_isExtendedRunningWithRemaining() {
        val duration = 900L
        controller.applyMessage(
            ExtendedBolusStatusV2Response(1, 78, pumpTimestampAt(now), 2300, duration, 0, 0)
        )

        assertEquals(SessionPhase.EXTENDED_RUNNING, controller.session?.phase)
        assertEquals(78, controller.session?.bolusId)
        assertRemaining(duration)
    }

    @Test
    fun extendedBolusEmpty_keepsCurrentDeliveringPhase() {
        controller.applyMessage(deliveringBolus(77))
        controller.applyMessage(ExtendedBolusStatusResponse(0, 0, 0, 0, 0, 0))

        assertEquals(SessionPhase.DELIVERING, controller.session?.phase)
    }

    // ---------- -94 BolusPermission ----------

    @Test
    fun permissionWithId_goesAwaitingPhoneThenDelivering() {
        controller.applyMessage(BolusPermissionResponse(0, 77, 0))
        assertEquals(SessionPhase.AWAITING_PHONE, controller.session?.phase)
        assertEquals(77, controller.session?.bolusId)

        controller.applyMessage(deliveringBolus(77))
        assertEquals(SessionPhase.DELIVERING, controller.session?.phase)
    }

    @Test
    fun permissionWithoutId_staysAwaitingPhone() {
        controller.applyMessage(BolusPermissionResponse(0, 0, 0))

        assertEquals(SessionPhase.AWAITING_PHONE, controller.session?.phase)
        assertNull(controller.session?.bolusId)
    }

    @Test
    fun permissionNack_failsWithNack() {
        controller.applyMessage(BolusPermissionResponse(1, 0, 1))

        assertEquals(SessionPhase.FAILED, controller.session?.phase)
        assertEquals(BolusOutcome.Failed(BolusError.NACK), controller.outcome)
    }

    // ---------- -96 CancelBolus ----------

    @Test
    fun cancelSuccess_isDoneAndCancelled() {
        controller.applyMessage(deliveringBolus(77))
        controller.applyMessage(CancelBolusResponse(0, 77, 0))

        assertEquals(SessionPhase.DONE, controller.session?.phase)
        assertEquals(BolusOutcome.Cancelled, controller.outcome)
    }

    @Test
    fun cancelFailure_isFailedWithNack() {
        controller.applyMessage(deliveringBolus(77))
        controller.applyMessage(CancelBolusResponse(1, 77, 2))

        assertEquals(SessionPhase.FAILED, controller.session?.phase)
        assertEquals(
            BolusOutcome.Failed(BolusError.NACK, statusId = 1, reasonId = 2),
            controller.outcome,
        )
    }

    // ---------- 48 LastBolusStatus ----------

    @Test
    fun lastBolusComplete_matchingId_isDoneSuccess() {
        controller.applyMessage(deliveringBolus(77))
        controller.applyMessage(LastBolusStatusV2Response(1, 77, 0, 2300, 3, 0, 0, 0, 2300))

        assertEquals(SessionPhase.DONE, controller.session?.phase)
        assertEquals(BolusOutcome.Success, controller.outcome)
    }

    @Test
    fun lastBolusOtherId_isIgnored() {
        controller.applyMessage(deliveringBolus(77))
        controller.applyMessage(LastBolusStatusV2Response(1, 78, 0, 2300, 3, 0, 0, 0, 2300))

        assertEquals(SessionPhase.DELIVERING, controller.session?.phase)
        assertEquals(BolusOutcome.Pending, controller.outcome)
    }

    // ---------- 未知 / 字段缺失 ----------

    @Test
    fun unknownMessage_doesNotChangeState() {
        controller.applyMessage(deliveringBolus(77))
        controller.applyMessage(UnknownMessage())

        assertEquals(SessionPhase.DELIVERING, controller.session?.phase)
        assertEquals(77, controller.session?.bolusId)
    }

    @Test
    fun emptyFields_doNotThrowAndKeepState() {
        controller.applyMessage(deliveringBolus(77))
        // 默认构造 = 字段全 0 的「缺字段」报文，只应被忽略
        controller.applyMessage(ExtendedBolusStatusResponse())
        controller.applyMessage(LastBolusStatusResponse(0, 0, 0, 0, 0, 0, 0, 0, byteArrayOf()))

        assertEquals(SessionPhase.DELIVERING, controller.session?.phase)
        assertEquals(77, controller.session?.bolusId)
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
