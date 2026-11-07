package com.pumppan.controlx2.bolus

import com.jwoglom.controlx2.R
import com.pumppan.controlx2.bolus.state.BolusDelivery
import com.pumppan.controlx2.bolus.state.BolusDeliveryState
import com.pumppan.controlx2.bolus.state.BolusError
import com.pumppan.controlx2.bolus.state.BolusOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BolusNotificationsContentTest {

    /**
     * 旧 `SessionPhase` 已并入单一 [BolusDeliveryState]：
     * - DRAFT / PERMITTING → 尚未有会话快照，统一用未落定的 `IDLE` 表示（无通知）；
     * - DONE / ABORTED / FAILED → `finishedAtMillis` 非空，具体标题由 [BolusOutcome] 决定。
     */
    private fun delivery(
        state: BolusDeliveryState,
        finished: Boolean = false,
        extendedRunning: Boolean = false,
    ) = BolusDelivery(
        state = state,
        bolusId = 1,
        hasImmediateLeg = !extendedRunning,
        expectedEndAtMillis = if (extendedRunning) 1_000_000L else null,
        finishedAtMillis = if (finished) 1_000_000L else null,
    )

    private fun content(
        delivery: BolusDelivery,
        outcome: BolusOutcome = BolusOutcome.Pending,
        connected: Boolean = true,
        dose: String? = "2.50",
        extendedDose: String? = null,
        start: String? = "12:30",
        end: String? = null,
    ) = contentFor(delivery, outcome, connected, dose, extendedDose, start, end)

    @Test
    fun draft_and_permitting_have_noNotification() {
        // 两个旧相位都没有已发起的会话；新模型统一表现为未落定的 IDLE。
        assertNull(content(delivery(BolusDeliveryState.IDLE)))
        assertNull(content(delivery(BolusDeliveryState.IDLE)))
    }

    @Test
    fun awaitingPhone_showsPreparingWithAbsoluteUnits() {
        val c = content(delivery(BolusDeliveryState.CONFIRMING))!!
        assertEquals(R.string.bolus_notif_title_preparing, c.titleRes)
        assertEquals(R.string.bolus_notif_body_preparing, c.textRes)
        assertEquals(listOf("2.50"), c.textArgs)
    }

    @Test
    fun delivering_includesDoseAndStartTime() {
        val c = content(delivery(BolusDeliveryState.ACTIVE))!!
        assertEquals(R.string.bolus_notif_title_delivering, c.titleRes)
        assertEquals(R.string.bolus_notif_body_delivering, c.textRes)
        assertEquals(listOf("2.50", "12:30"), c.textArgs)
    }

    @Test
    fun delivering_withoutStartTime_usesDoseOnlyBody() {
        val c = content(delivery(BolusDeliveryState.ACTIVE), start = null)!!
        assertEquals(R.string.bolus_notif_body_delivering_no_start, c.textRes)
        assertEquals(listOf("2.50"), c.textArgs)
    }

    @Test
    fun extended_showsExtendedDoseAndExpectedEnd() {
        val c = content(
            delivery(BolusDeliveryState.ACTIVE, extendedRunning = true),
            dose = "1.00",
            extendedDose = "2.00",
            end = "14:30",
        )!!
        assertEquals(R.string.bolus_notif_title_extended, c.titleRes)
        assertEquals(R.string.bolus_notif_body_extended, c.textRes)
        assertEquals(listOf("2.00", "14:30"), c.textArgs)
    }

    @Test
    fun cancelling_showsWaitingForPump() {
        val c = content(delivery(BolusDeliveryState.CANCELLING))!!
        assertEquals(R.string.bolus_notif_title_cancelling, c.titleRes)
        assertEquals(R.string.bolus_notif_body_cancelling, c.textRes)
    }

    @Test
    fun doneSuccess_showsCompleted() {
        val c = content(delivery(BolusDeliveryState.IDLE, finished = true), BolusOutcome.Success)!!
        assertEquals(R.string.bolus_notif_title_done, c.titleRes)
        assertEquals(R.string.bolus_notif_body_done, c.textRes)
    }

    @Test
    fun doneCancelled_showsStopped() {
        val c = content(delivery(BolusDeliveryState.IDLE, finished = true), BolusOutcome.Cancelled)!!
        assertEquals(R.string.bolus_notif_title_stopped, c.titleRes)
        assertEquals(R.string.bolus_notif_body_stopped, c.textRes)
    }

    @Test
    fun failed_keepsRetryHint() {
        val c = content(
            delivery(BolusDeliveryState.IDLE, finished = true),
            BolusOutcome.Failed(BolusError.NACK),
        )!!
        assertEquals(R.string.bolus_notif_title_failed, c.titleRes)
        assertEquals(R.string.bolus_notif_body_failed, c.textRes)
    }

    @Test
    fun failedFromCancel_asksToCheckPump() {
        val c = content(
            delivery(BolusDeliveryState.UNKNOWN, finished = true),
            BolusOutcome.Failed(BolusError.TIMEOUT, fromCancel = true),
        )!!
        assertEquals(R.string.bolus_notif_body_failed_cancel, c.textRes)
    }

    @Test
    fun disconnectedDuringDelivery_hidesDeliveryClaim() {
        val c = content(delivery(BolusDeliveryState.ACTIVE), connected = false)!!
        assertEquals(R.string.bolus_notif_title_unknown, c.titleRes)
        assertEquals(R.string.bolus_notif_body_unknown_disconnected, c.textRes)
        assertEquals(emptyList<String>(), c.textArgs)
    }

    @Test
    fun unknown_usesConnectedVariant() {
        assertEquals(
            R.string.bolus_notif_body_unknown,
            content(delivery(BolusDeliveryState.UNKNOWN), connected = true)!!.textRes,
        )
        assertEquals(
            R.string.bolus_notif_body_unknown_disconnected,
            content(delivery(BolusDeliveryState.UNKNOWN), connected = false)!!.textRes,
        )
    }
}
