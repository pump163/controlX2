package com.pumppan.controlx2.bolus.state

import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BolusPollSchedulerTest {

    private var currentSent = 0
    private var extendedSent = 0

    private val scheduler = BolusPollScheduler(
        requestCurrentBolusStatus = { currentSent++ },
        requestExtendedBolusStatus = { extendedSent++ },
    )

    @Test
    fun activeImmediate_singleFlightThenRetryAfterTimeout() {
        scheduler.reset(1_000L)
        val delivery = immediateActive()

        // 未到 10 秒间隔 → 不发。
        assertFalse(scheduler.tick(delivery, connected = true, now = 1_000L))
        assertTrue(scheduler.tick(delivery, connected = true, now = 11_000L))
        assertEquals(1, currentSent)

        // 同一 opCode 已有请求在飞且未超时 → 不重发。
        assertFalse(scheduler.tick(delivery, connected = true, now = 11_500L))

        // 满 10 秒超时、且间隔满足 → 允许重发。
        assertTrue(scheduler.tick(delivery, connected = true, now = 21_000L))
        assertEquals(2, currentSent)
    }

    @Test
    fun responseClearsInFlight_butIntervalStillDedupes() {
        scheduler.reset(0L)
        val delivery = immediateActive()
        assertTrue(scheduler.tick(delivery, connected = true, now = 10_000L))

        scheduler.onMessage(CurrentBolusStatusResponse(1, 77, 0, 2300, 0, 0))
        // 响应销掉「在飞」，但 10 秒去重窗口仍拦截。
        assertFalse(scheduler.tick(delivery, connected = true, now = 10_500L))
        assertTrue(scheduler.tick(delivery, connected = true, now = 20_000L))
    }

    @Test
    fun pureSquare_pollsExtendedEveryFiveMinutes_andNeverCurrent() {
        scheduler.reset(0L)
        val delivery = BolusDelivery(
            state = BolusDeliveryState.ACTIVE,
            bolusId = 77,
            hasImmediateLeg = false,
            expectedEndAtMillis = 1_000_000L,
        )

        assertTrue(scheduler.tick(delivery, connected = true, now = 300_000L))
        assertEquals(0, currentSent)
        assertEquals(1, extendedSent)

        assertFalse(scheduler.tick(delivery, connected = true, now = 300_001L))
        assertTrue(scheduler.tick(delivery, connected = true, now = 600_000L))
        assertEquals(2, extendedSent)
    }

    @Test
    fun confirmingExtended_usesImmediateInterval() {
        scheduler.reset(0L)
        val delivery = BolusDelivery(
            state = BolusDeliveryState.CONFIRMING,
            bolusId = 77,
            hasImmediateLeg = false,
            expectedEndAtMillis = 1_000_000L,
        )

        assertTrue(scheduler.tick(delivery, connected = true, now = 10_000L))
        assertEquals(1, extendedSent)
    }

    @Test
    fun disconnectedOrFinished_sendsNothing() {
        scheduler.reset(0L)
        assertFalse(scheduler.tick(immediateActive(), connected = false, now = 10_000L))
        assertEquals(0, currentSent)

        assertFalse(scheduler.tick(immediateActive().copy(finishedAtMillis = 1L), true, 10_000L))
        assertEquals(0, currentSent)
        assertEquals(0, extendedSent)
    }

    @Test
    fun markReconciled_suppressesImmediatePollUntilNextInterval() {
        scheduler.reset(0L)
        val delivery = immediateActive()
        assertTrue(scheduler.tick(delivery, connected = true, now = 10_000L))

        scheduler.markReconciled(10_000L)
        assertFalse(scheduler.tick(delivery, connected = true, now = 10_500L))
        assertTrue(scheduler.tick(delivery, connected = true, now = 20_000L))
    }

    private fun immediateActive() = BolusDelivery(
        state = BolusDeliveryState.ACTIVE,
        bolusId = 77,
        hasImmediateLeg = true,
        immediateLegDoneAtMillis = null,
        expectedEndAtMillis = null,
    )
}
