package com.pumppan.controlx2.bolus.state

import org.junit.Assert.assertEquals
import org.junit.Test

class BolusSessionHolderTest {

    private var now = 1_000_000L
    private val holder = BolusSessionHolder(clock = { now })

    @Test
    fun noSession_connected_isIdleAndRowHidden() {
        assertEquals(BolusActivity.IDLE, holder.isAnyBolusActive(connected = true))
        assertEquals(CancelRowState.HIDDEN, holder.cancelRowState(connected = true))
    }

    @Test
    fun deliveringWithId_isActiveAndCancelReady() {
        holder.begin(
            phase = SessionPhase.DELIVERING,
            bolusId = 42,
            expectedEndAtMillis = now + 60_000,
            now = now,
        )
        assertEquals(BolusActivity.ACTIVE, holder.isAnyBolusActive(connected = true))
        assertEquals(CancelRowState.READY, holder.cancelRowState(connected = true))
    }

    @Test
    fun deliveringWithoutId_needsIdBeforeCancel() {
        holder.begin(phase = SessionPhase.EXTENDED_RUNNING, bolusId = null, now = now)
        assertEquals(BolusActivity.ACTIVE, holder.isAnyBolusActive(connected = true))
        assertEquals(CancelRowState.NEEDS_ID, holder.cancelRowState(connected = true))
    }

    @Test
    fun disconnectedWithTrace_isUnknownAndReadOnly() {
        holder.begin(phase = SessionPhase.DELIVERING, bolusId = 42, now = now)
        assertEquals(BolusActivity.UNKNOWN, holder.isAnyBolusActive(connected = false))
        assertEquals(CancelRowState.READ_ONLY, holder.cancelRowState(connected = false))
    }

    @Test
    fun disconnectedWithoutTrace_isUnknownAndRowHidden() {
        assertEquals(BolusActivity.UNKNOWN, holder.isAnyBolusActive(connected = false))
        assertEquals(CancelRowState.HIDDEN, holder.cancelRowState(connected = false))
    }

    @Test
    fun reconciling_isReconciling() {
        holder.begin(phase = SessionPhase.DELIVERING, bolusId = 42, now = now)
        holder.markReconciling(true)
        assertEquals(CancelRowState.RECONCILING, holder.cancelRowState(connected = true))
        holder.markReconciling(false)
        assertEquals(CancelRowState.READY, holder.cancelRowState(connected = true))
    }

    @Test
    fun staleWindow_boundaryIsStillActive() {
        holder.begin(phase = SessionPhase.DELIVERING, bolusId = 42, now = now)
        now += BolusLimits.STALE_ACTIVE_WINDOW_SECONDS * 1000L
        assertEquals(BolusActivity.ACTIVE, holder.isAnyBolusActive(connected = true))

        now += 1
        assertEquals(BolusActivity.UNKNOWN, holder.isAnyBolusActive(connected = true))
    }

    @Test
    fun evidenceRefreshesStaleTimer() {
        holder.begin(phase = SessionPhase.DELIVERING, bolusId = 42, now = now)
        now += 599_000
        holder.onEvidence(phase = SessionPhase.DELIVERING, now = now)
        assertEquals(BolusActivity.ACTIVE, holder.isAnyBolusActive(connected = true))
    }

    @Test
    fun expectedEndReached_isIdleAndRowHidden() {
        holder.begin(
            phase = SessionPhase.EXTENDED_RUNNING,
            bolusId = 42,
            expectedEndAtMillis = now + 10_000,
            now = now,
        )
        now += 10_001
        assertEquals(BolusActivity.IDLE, holder.isAnyBolusActive(connected = true))
        assertEquals(CancelRowState.HIDDEN, holder.cancelRowState(connected = true))
    }

    @Test
    fun finishedPhases_areIdle() {
        for (phase in listOf(SessionPhase.DONE, SessionPhase.ABORTED, SessionPhase.FAILED)) {
            holder.begin(phase = phase, bolusId = 42, now = now)
            assertEquals(BolusActivity.IDLE, holder.isAnyBolusActive(connected = true))
            assertEquals(CancelRowState.HIDDEN, holder.cancelRowState(connected = true))
        }
    }

    @Test
    fun clear_resetsSessionAndReconciling() {
        holder.begin(phase = SessionPhase.DELIVERING, bolusId = 42, now = now)
        holder.markReconciling(true)
        holder.clear()
        assertEquals(BolusActivity.IDLE, holder.isAnyBolusActive(connected = true))
        assertEquals(CancelRowState.HIDDEN, holder.cancelRowState(connected = true))
    }
}
