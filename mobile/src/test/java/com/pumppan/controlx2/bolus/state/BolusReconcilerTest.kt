package com.pumppan.controlx2.bolus.state

import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.helpers.Dates
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusV2Response
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.LastBolusStatusV2Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class BolusReconcilerTest {

    private var now = 1_800_000_000_000L
    private val sender = FakeSender()
    private val controller = BolusSessionController(mode = { BolusControllerMode.V2 }, clock = { now })
    private val holder = controller.holder
    private val reconciler = BolusReconciler(
        sender = sender,
        holder = holder,
        mode = { BolusControllerMode.V2 },
        clock = { now },
    )

    @Test
    fun normalFourSteps_idle() {
        reconciler.start(now)
        assertEquals(listOf(ReconcileStep.CURRENT), sender.sent)

        feed(idleCurrent())
        assertEquals(listOf(ReconcileStep.CURRENT, ReconcileStep.EXTENDED), sender.sent)

        feed(emptyExtended())
        assertEquals(listOf(ReconcileStep.CURRENT, ReconcileStep.EXTENDED, ReconcileStep.EXTENDED_V2), sender.sent)

        feed(emptyExtendedV2())
        assertEquals(4, sender.sent.size)
        assertEquals(ReconcileStep.LAST, sender.sent.last())

        feed(emptyLast())
        assertFalse(reconciler.running)
        assertEquals(BolusActivity.IDLE, reconciler.result)
        assertNull(holder.session)
        assertFalse(holder.reconciling)
    }

    @Test
    fun normalFourSteps_activeWithBolusId() {
        reconciler.start(now)
        feed(CurrentBolusStatusResponse(1, 77, 0, 2300, 0, 0))
        feed(emptyExtended())
        feed(emptyExtendedV2())
        feed(emptyLast())

        assertEquals(BolusActivity.ACTIVE, reconciler.result)
        assertEquals(SessionPhase.DELIVERING, holder.session?.phase)
        assertEquals(77, holder.session?.bolusId)
        assertEquals(BolusActivity.ACTIVE, holder.isAnyBolusActive(connected = true))
        assertEquals(CancelRowState.READY, holder.cancelRowState(connected = true))
    }

    @Test
    fun extendedRunning_isExtendedRunningWithRemaining() {
        val duration = 900L
        reconciler.start(now)
        feed(idleCurrent())
        feed(ExtendedBolusStatusResponse(1, 78, pumpTimestampAt(now), 2300, duration, 0))
        feed(emptyExtendedV2())
        feed(emptyLast())

        assertEquals(BolusActivity.ACTIVE, reconciler.result)
        assertEquals(SessionPhase.EXTENDED_RUNNING, holder.session?.phase)
        val end = holder.session?.expectedEndAtMillis
        assertTrue("延展段应算出剩余时间", end != null && end > now)
    }

    @Test
    fun lastStepTimeout_skipsAndStillFallsIdle() {
        reconciler.start(now)
        feed(idleCurrent())
        feed(emptyExtended())
        feed(emptyExtendedV2())
        assertEquals(ReconcileStep.LAST, reconciler.currentStep)

        now += 2_000
        reconciler.onTick(now)
        assertFalse(reconciler.running)
        assertEquals(BolusActivity.IDLE, reconciler.result)
        assertEquals(4, sender.sent.size)
    }

    @Test
    fun extendedStepTimeout_skipsButFallsUnknown() {
        reconciler.start(now)
        feed(idleCurrent())
        assertEquals(ReconcileStep.EXTENDED, reconciler.currentStep)

        now += 2_000
        reconciler.onTick(now)
        assertEquals(ReconcileStep.EXTENDED_V2, reconciler.currentStep)

        feed(emptyExtendedV2())
        feed(emptyLast())

        assertFalse(reconciler.running)
        assertEquals(BolusActivity.UNKNOWN, reconciler.result)
        assertEquals(SessionPhase.UNKNOWN, holder.session?.phase)
        assertEquals(BolusActivity.UNKNOWN, holder.isAnyBolusActive(connected = true))
        assertEquals(CancelRowState.READ_ONLY, holder.cancelRowState(connected = true))
    }

    @Test
    fun overallTimeout_fallsUnknownNotIdle() {
        reconciler.start(now)
        now += 5_000
        reconciler.onTick(now)

        assertFalse(reconciler.running)
        assertEquals(BolusActivity.UNKNOWN, reconciler.result)
        assertEquals(SessionPhase.UNKNOWN, holder.session?.phase)
        assertEquals(BolusActivity.UNKNOWN, holder.isAnyBolusActive(connected = true))
        assertEquals(CancelRowState.READ_ONLY, holder.cancelRowState(connected = true))
    }

    @Test
    fun reconciling_cancelRowIsReadOnlyAndActivityUnknown() {
        controller.applyMessage(CurrentBolusStatusResponse(1, 77, 0, 2300, 0, 0))
        assertEquals(BolusActivity.ACTIVE, holder.isAnyBolusActive(connected = true))

        reconciler.start(now)
        assertEquals(BolusActivity.UNKNOWN, holder.isAnyBolusActive(connected = true))
        assertEquals(CancelRowState.RECONCILING, holder.cancelRowState(connected = true))

        // 对账期间收到 44 Idle：会话清空，但「对账中」不能被提前结束
        feed(idleCurrent())
        assertTrue(holder.reconciling)
        assertEquals(CancelRowState.RECONCILING, holder.cancelRowState(connected = true))
        assertEquals(BolusActivity.UNKNOWN, holder.isAnyBolusActive(connected = true))
    }

    @Test
    fun afterReconcile_newEvidenceFlipsState() {
        reconciler.start(now)
        feed(idleCurrent())
        feed(emptyExtended())
        feed(emptyExtendedV2())
        feed(emptyLast())
        assertEquals(BolusActivity.IDLE, reconciler.result)

        controller.applyMessage(CurrentBolusStatusResponse(1, 88, 0, 2300, 0, 0))
        assertEquals(SessionPhase.DELIVERING, holder.session?.phase)
        assertEquals(BolusActivity.ACTIVE, holder.isAnyBolusActive(connected = true))

        controller.applyMessage(idleCurrent())
        assertEquals(BolusActivity.IDLE, holder.isAnyBolusActive(connected = true))
    }

    @Test
    fun legacyMode_sendsNoRequests() {
        val legacy = BolusReconciler(
            sender = sender,
            holder = holder,
            mode = { BolusControllerMode.LEGACY },
            clock = { now },
        )
        legacy.start(now)

        assertTrue(sender.sent.isEmpty())
        assertFalse(legacy.running)
        assertNull(legacy.result)
        assertFalse(holder.reconciling)
    }

    // ---------- helpers ----------

    private fun feed(message: Message) {
        controller.applyMessage(message)
        reconciler.onMessage(message, now)
    }

    private fun idleCurrent() = CurrentBolusStatusResponse(0, 0, 0, 0, 0, 0)

    private fun emptyExtended() = ExtendedBolusStatusResponse(0, 0, 0, 0, 0, 0)

    private fun emptyExtendedV2() = ExtendedBolusStatusV2Response(0, 0, 0, 0, 0, 0, 0)

    private fun emptyLast() = LastBolusStatusV2Response(0, 0, 0, 0, 0, 0, 0, 0, 0)

    /** 与 S1 单测同样的往返换算：泵墙上时间 + 本机偏移 → 本机 epoch。 */
    private fun pumpTimestampAt(millis: Long): Long {
        val offsetSeconds = TimeZone.getDefault().getOffset(millis) / 1000
        return Dates.fromInstantToJan12008EpochSeconds(Instant.ofEpochMilli(millis + offsetSeconds * 1000))
    }

    private class FakeSender : BolusStatusRequestSender {
        val sent = mutableListOf<ReconcileStep>()

        override fun requestCurrentBolusStatus() {
            sent += ReconcileStep.CURRENT
        }

        override fun requestExtendedBolusStatus() {
            sent += ReconcileStep.EXTENDED
        }

        override fun requestExtendedBolusStatusV2() {
            sent += ReconcileStep.EXTENDED_V2
        }

        override fun requestLastBolusStatus() {
            sent += ReconcileStep.LAST
        }
    }
}
