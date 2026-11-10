package com.pumppan.controlx2.bolus.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            state = BolusDeliveryState.ACTIVE,
            bolusId = 42,
            expectedEndAtMillis = now + 60_000,
            now = now,
        )
        assertEquals(BolusActivity.ACTIVE, holder.isAnyBolusActive(connected = true))
        assertEquals(CancelRowState.READY, holder.cancelRowState(connected = true))
    }

    @Test
    fun deliveringWithoutId_needsIdBeforeCancel() {
        holder.begin(state = BolusDeliveryState.ACTIVE, bolusId = null, now = now)
        assertEquals(BolusActivity.ACTIVE, holder.isAnyBolusActive(connected = true))
        assertEquals(CancelRowState.NEEDS_ID, holder.cancelRowState(connected = true))
    }

    @Test
    fun disconnectedWithTrace_isUnknownAndReadOnly() {
        holder.begin(state = BolusDeliveryState.ACTIVE, bolusId = 42, now = now)
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
        holder.begin(state = BolusDeliveryState.ACTIVE, bolusId = 42, now = now)
        holder.markReconciling(true)
        assertEquals(CancelRowState.RECONCILING, holder.cancelRowState(connected = true))
        holder.markReconciling(false)
        assertEquals(CancelRowState.READY, holder.cancelRowState(connected = true))
    }

    @Test
    fun staleWindow_boundaryIsStillActive() {
        holder.begin(state = BolusDeliveryState.ACTIVE, bolusId = 42, now = now)
        now += BolusLimits.STALE_IMMEDIATE_MILLIS
        assertEquals(BolusActivity.ACTIVE, holder.isAnyBolusActive(connected = true))

        now += 1
        assertEquals(BolusActivity.UNKNOWN, holder.isAnyBolusActive(connected = true))
    }

    @Test
    fun evidenceRefreshesStaleTimer() {
        holder.begin(state = BolusDeliveryState.ACTIVE, bolusId = 42, now = now)
        now += BolusLimits.STALE_IMMEDIATE_MILLIS - 1_000L
        // 状态机内每条新证据都经由 holder.update 刷新 lastEvidenceAtMillis。
        holder.update(now) { it }
        assertEquals(BolusActivity.ACTIVE, holder.isAnyBolusActive(connected = true))
    }

    @Test
    fun expectedEndPassed_withoutEndEvidence_staysActiveUntilGraceThenUnknown() {
        holder.begin(
            state = BolusDeliveryState.ACTIVE,
            bolusId = 42,
            hasImmediateLeg = false,
            expectedEndAtMillis = now + 10_000,
            now = now,
        )
        // 新模型：预计结束时刻不是结束判据（要 47 归零 / -91），过了仍按 ACTIVE 处理。
        now += 10_001
        assertEquals(BolusActivity.ACTIVE, holder.isAnyBolusActive(connected = true))
        assertEquals(CancelRowState.READY, holder.cancelRowState(connected = true))

        // 预计结束 + 5 分钟兜底仍无结束证据 → UNKNOWN。
        now += 5 * 60_000L
        assertEquals(BolusActivity.UNKNOWN, holder.isAnyBolusActive(connected = true))
    }

    @Test
    fun finishedStates_areIdleAndRowHidden() {
        // 旧 DONE / ABORTED / FAILED 对应新模型的「已落定」（finishedAtMillis 非空）。
        val finished = listOf(
            BolusDelivery(state = BolusDeliveryState.IDLE, outcome = BolusOutcome.Success),
            BolusDelivery(state = BolusDeliveryState.CANCELLED, outcome = BolusOutcome.Cancelled),
            BolusDelivery(state = BolusDeliveryState.IDLE, outcome = BolusOutcome.Failed(BolusError.NACK)),
        )
        for (delivery in finished) {
            holder.set(delivery.copy(bolusId = 42, finishedAtMillis = now, lastEvidenceAtMillis = now))
            assertEquals(BolusActivity.IDLE, holder.isAnyBolusActive(connected = true))
            assertEquals(CancelRowState.HIDDEN, holder.cancelRowState(connected = true))
        }
    }

    @Test
    fun cancelThrottle_threeAttemptsInFifteenSecondWindow() {
        holder.begin(state = BolusDeliveryState.ACTIVE, bolusId = 42, now = now)
        assertEquals(CancelDecision.Send(42), holder.requestCancel(connected = true, now = now))
        assertEquals(CancelDecision.Send(42), holder.requestCancel(connected = true, now = now + 1_000))
        assertEquals(CancelDecision.Send(42), holder.requestCancel(connected = true, now = now + 2_000))

        // 第 4 次仍在 15s 窗口内 → 节流（旧实现 5s 窗口会在这里放行）。
        assertTrue(holder.requestCancel(connected = true, now = now + 14_999) is CancelDecision.Throttled)

        // 第 1 次尝试滑出 15s 窗口 → 允许再发。
        assertEquals(CancelDecision.Send(42), holder.requestCancel(connected = true, now = now + 15_000))
    }

    @Test
    fun clear_resetsSessionAndReconciling() {
        holder.begin(state = BolusDeliveryState.ACTIVE, bolusId = 42, now = now)
        holder.markReconciling(true)
        holder.clear()
        assertEquals(BolusActivity.IDLE, holder.isAnyBolusActive(connected = true))
        assertEquals(CancelRowState.HIDDEN, holder.cancelRowState(connected = true))
    }

    @Test
    fun begin_overwritingExtendedOnlySession_keepsPriorSnapshot() {
        holder.begin(
            state = BolusDeliveryState.ACTIVE,
            bolusId = 42,
            expectedEndAtMillis = now + 60_000,
            now = now,
        )
        // 双波立即段结束、延展段仍在跑：泵允许再发起立即大剂量，旧会话会被覆盖。
        holder.update(now) { it.copy(immediateLegDoneAtMillis = now) }
        assertTrue(holder.session!!.isExtendedRunning)

        holder.begin(
            state = BolusDeliveryState.ACTIVE,
            bolusId = 43,
            expectedEndAtMillis = now + 60_000,
            now = now,
        )
        assertEquals(42, holder.priorExtended?.bolusId)
        assertEquals(43, holder.session?.bolusId)
    }

    @Test
    fun begin_sameIdRecovery_doesNotReportSecondSession() {
        holder.begin(
            state = BolusDeliveryState.ACTIVE,
            bolusId = 42,
            expectedEndAtMillis = now + 60_000,
            now = now,
        )
        holder.update(now) { it.copy(immediateLegDoneAtMillis = now) }
        // 对账 / 同笔恢复用同一个 id 重开：不能误报「另有一笔延展」。
        holder.begin(
            state = BolusDeliveryState.ACTIVE,
            bolusId = 42,
            expectedEndAtMillis = now + 60_000,
            now = now,
        )
        assertNull(holder.priorExtended)
    }

    @Test
    fun clear_dropsPriorSnapshot() {
        holder.begin(
            state = BolusDeliveryState.ACTIVE,
            bolusId = 42,
            expectedEndAtMillis = now + 60_000,
            now = now,
        )
        holder.update(now) { it.copy(immediateLegDoneAtMillis = now) }
        holder.begin(
            state = BolusDeliveryState.ACTIVE,
            bolusId = 43,
            expectedEndAtMillis = now + 60_000,
            now = now,
        )
        holder.clear()
        assertNull(holder.priorExtended)
    }
}
