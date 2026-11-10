package com.pumppan.controlx2.bolus.state

import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ExtendedBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse
import com.pumppan.controlx2.query.PumpCommandPort
import com.pumppan.controlx2.query.PumpRequestSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 改造后由 [PumpCommandPort] 负责单飞 / 超时 / 节流，本测试同时守住两件事：
 * 1. 调度器的业务判定（立即段 / 延展段 / 确认阶梯 / 断连 / 页面门控）没变；
 * 2. 端口接管后的节奏与改造前逐条等价（这是「V2 已跑通」不得被破坏的证据）。
 */
class BolusPollSchedulerTest {

    private var currentSent = 0
    private var extendedSent = 0

    private val port = PumpCommandPort(object : PumpRequestSender {
        override fun send(type: SendType, messages: List<Message>): Boolean {
            when (messages.first()) {
                is CurrentBolusStatusRequest -> currentSent++
                is ExtendedBolusStatusRequest -> extendedSent++
            }
            return true
        }
    })

    private val scheduler = BolusPollScheduler(
        port = port,
        currentRequest = { CurrentBolusStatusRequest() },
        extendedRequest = { ExtendedBolusStatusRequest() },
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
