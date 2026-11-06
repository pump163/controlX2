package com.pumppan.controlx2.bolus

import com.jwoglom.controlx2.R
import com.pumppan.controlx2.bolus.state.BolusError
import com.pumppan.controlx2.bolus.state.BolusOutcome
import com.pumppan.controlx2.bolus.state.SessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BolusNotificationsContentTest {

    private fun content(
        phase: SessionPhase,
        outcome: BolusOutcome = BolusOutcome.Pending,
        connected: Boolean = true,
        dose: String? = "2.50",
        extendedDose: String? = null,
        start: String? = "12:30",
        end: String? = null,
    ) = contentFor(phase, outcome, connected, dose, extendedDose, start, end)

    @Test
    fun draft_and_permitting_have_noNotification() {
        assertNull(content(SessionPhase.DRAFT))
        assertNull(content(SessionPhase.PERMITTING))
    }

    @Test
    fun awaitingPhone_showsPreparingWithAbsoluteUnits() {
        val c = content(SessionPhase.AWAITING_PHONE)!!
        assertEquals(R.string.bolus_notif_title_preparing, c.titleRes)
        assertEquals(R.string.bolus_notif_body_preparing, c.textRes)
        assertEquals(listOf("2.50"), c.textArgs)
    }

    @Test
    fun delivering_includesDoseAndStartTime() {
        val c = content(SessionPhase.DELIVERING)!!
        assertEquals(R.string.bolus_notif_title_delivering, c.titleRes)
        assertEquals(R.string.bolus_notif_body_delivering, c.textRes)
        assertEquals(listOf("2.50", "12:30"), c.textArgs)
    }

    @Test
    fun delivering_withoutStartTime_usesDoseOnlyBody() {
        val c = content(SessionPhase.DELIVERING, start = null)!!
        assertEquals(R.string.bolus_notif_body_delivering_no_start, c.textRes)
        assertEquals(listOf("2.50"), c.textArgs)
    }

    @Test
    fun extended_showsExtendedDoseAndExpectedEnd() {
        val c = content(
            SessionPhase.EXTENDED_RUNNING,
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
        val c = content(SessionPhase.CANCELLING)!!
        assertEquals(R.string.bolus_notif_title_cancelling, c.titleRes)
        assertEquals(R.string.bolus_notif_body_cancelling, c.textRes)
    }

    @Test
    fun doneSuccess_showsCompleted() {
        val c = content(SessionPhase.DONE, BolusOutcome.Success)!!
        assertEquals(R.string.bolus_notif_title_done, c.titleRes)
        assertEquals(R.string.bolus_notif_body_done, c.textRes)
    }

    @Test
    fun doneCancelled_showsStopped() {
        val c = content(SessionPhase.DONE, BolusOutcome.Cancelled)!!
        assertEquals(R.string.bolus_notif_title_stopped, c.titleRes)
        assertEquals(R.string.bolus_notif_body_stopped, c.textRes)
    }

    @Test
    fun failed_keepsRetryHint() {
        val c = content(SessionPhase.FAILED, BolusOutcome.Failed(BolusError.NACK))!!
        assertEquals(R.string.bolus_notif_title_failed, c.titleRes)
        assertEquals(R.string.bolus_notif_body_failed, c.textRes)
    }

    @Test
    fun failedFromCancel_asksToCheckPump() {
        val c = content(
            SessionPhase.FAILED,
            BolusOutcome.Failed(BolusError.TIMEOUT, fromCancel = true),
        )!!
        assertEquals(R.string.bolus_notif_body_failed_cancel, c.textRes)
    }

    @Test
    fun disconnectedDuringDelivery_hidesDeliveryClaim() {
        val c = content(SessionPhase.DELIVERING, connected = false)!!
        assertEquals(R.string.bolus_notif_title_unknown, c.titleRes)
        assertEquals(R.string.bolus_notif_body_unknown_disconnected, c.textRes)
        assertEquals(emptyList<String>(), c.textArgs)
    }

    @Test
    fun unknown_usesConnectedVariant() {
        assertEquals(
            R.string.bolus_notif_body_unknown,
            content(SessionPhase.UNKNOWN, connected = true)!!.textRes,
        )
        assertEquals(
            R.string.bolus_notif_body_unknown_disconnected,
            content(SessionPhase.UNKNOWN, connected = false)!!.textRes,
        )
    }
}
