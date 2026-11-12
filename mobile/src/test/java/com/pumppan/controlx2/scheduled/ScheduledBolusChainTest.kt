package com.pumppan.controlx2.scheduled

import com.pumppan.controlx2.ledger.BolusLedgerPhase
import com.pumppan.controlx2.ledger.BolusStartObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链状态机的规则回归（设计 §4 / R2、R5、R6、R7、R8、R13、R17、R20、R22）。
 * 全部用纯 Kotlin 跑，不需要设备与泵。
 */
class ScheduledBolusChainTest {

    private val config = ScheduledBolusConfig(
        maxChainCount = 3,
        advanceNoticeMs = 120_000L,
        confirmTimeoutMs = 300_000L,
    )

    private fun chainOfTwo() = ScheduledBolusChain.start(
        chainId = "c1",
        links = listOf(
            ScheduledBolusChain.Link(1_000L, 600_000L),
            ScheduledBolusChain.Link(2_000L, 900_000L),
        ),
        anchorAtMillis = 10_000L,
        config = config,
    )

    private fun started(id: Int, at: Long) =
        BolusStartObservation(id, 2, BolusLedgerPhase.REQUESTING, at)

    @Test
    fun `countdown walks through advance notice and alerting`() {
        val base = chainOfTwo()
        assertEquals(ScheduledBolusChain.State.COUNTING, base.state)

        // T−2min 之前不动
        val early = ScheduledBolusChain.tick(base, 400_000L, config)
        assertEquals(ScheduledBolusChain.State.COUNTING, early.state)

        // 到 T−2min → 只提醒，不弹确认
        val notified = ScheduledBolusChain.tick(base, 490_000L, config)
        assertEquals(ScheduledBolusChain.State.NOTIFIED, notified.state)
        assertEquals(490_000L, notified.lastNotifiedAtMillis)

        // 到点 → ALERTING，并落 5 分钟确认截止
        val alerting = ScheduledBolusChain.tick(notified, 610_000L, config)
        assertEquals(ScheduledBolusChain.State.ALERTING, alerting.state)
        assertEquals(910_000L, alerting.confirmDeadlineAtMillis)
    }

    @Test
    fun `confirm timeout aborts the whole chain and never accepts a late confirm`() {
        val alerting = ScheduledBolusChain.tick(chainOfTwo(), 610_000L, config)
        val expired = ScheduledBolusChain.tick(alerting, 910_001L, config)

        assertEquals(ScheduledBolusChain.State.CHAIN_ABORTED, expired.state)
        assertEquals(ScheduledBolusChain.AbortReason.CONFIRM_TIMEOUT, expired.abortReason)

        // 晚确认不认：状态已是终态，confirm 原样返回
        val late = ScheduledBolusChain.confirm(expired, 920_000L, config)
        assertEquals(ScheduledBolusChain.State.CHAIN_ABORTED, late.state)
    }

    @Test
    fun `foreign bolus start evidence kills the chain`() {
        val chain = chainOfTwo()
        val foreign = ScheduledBolusChain.onStartObservation(chain, started(777, 100_000L))

        assertEquals(ScheduledBolusChain.State.CHAIN_ABORTED, foreign.state)
        assertEquals(ScheduledBolusChain.AbortReason.FOREIGN_BOLUS, foreign.abortReason)
        assertFalse(foreign.pendingBolusIds.contains(777))
    }

    @Test
    fun `two step confirm sends, and start evidence advances to next link anchored at start time`() {
        var chain = ScheduledBolusChain.tick(chainOfTwo(), 610_000L, config)
        assertEquals(ScheduledBolusChain.State.ALERTING, chain.state)

        chain = ScheduledBolusChain.confirm(chain, 620_000L, config)
        assertEquals(ScheduledBolusChain.State.WARNING, chain.state)

        chain = ScheduledBolusChain.secondConfirm(chain, 630_000L, config)
        assertEquals(ScheduledBolusChain.State.SENDING, chain.state)

        // 发送前先登记 bolusId（R22：先登记，防止报文先于本地状态）
        chain = ScheduledBolusChain.registerPermission(chain, 8801)
        assertTrue(chain.pendingBolusIds.contains(8801))

        // 泵确认开始 → ACTIVE，锚点为开始时刻
        chain = ScheduledBolusChain.onStartObservation(chain, started(8801, 700_000L))
        assertEquals(ScheduledBolusChain.State.ACTIVE, chain.state)
        assertEquals(700_000L, chain.anchorAtMillis)

        // 本笔完成 → 下一笔倒数，锚点仍是「本笔开始时刻」
        chain = ScheduledBolusChain.onTerminal(chain, 8801, ScheduledBolusChain.STATUS_COMPLETE)
        assertEquals(ScheduledBolusChain.State.COUNTING, chain.state)
        assertEquals(1, chain.index)
        assertEquals(700_000L, chain.anchorAtMillis)
        assertEquals(700_000L + 900_000L, chain.dueAtMillis)
        // 待发白名单随下一笔重建，旧 id 不再误判为外来
        assertTrue(chain.pendingBolusIds.contains(8801))
    }

    @Test
    fun `pump aborted bolus terminates the chain instead of counting again`() {
        var chain = ScheduledBolusChain.tick(chainOfTwo(), 610_000L, config)
        chain = ScheduledBolusChain.confirm(chain, 620_000L, config)
        chain = ScheduledBolusChain.secondConfirm(chain, 630_000L, config)
        chain = ScheduledBolusChain.registerPermission(chain, 8801)

        val aborted = ScheduledBolusChain.onTerminal(chain, 8801, 0) // STOPPED_USER_TERMINATED
        assertEquals(ScheduledBolusChain.State.CHAIN_ABORTED, aborted.state)
        assertEquals(ScheduledBolusChain.AbortReason.PUMP_ABORTED, aborted.abortReason)
        assertNull(aborted.confirmDeadlineAtMillis)
    }

    @Test
    fun `last link completes and chain is done`() {
        var chain = ScheduledBolusChain.tick(chainOfTwo(), 610_000L, config)
        chain = ScheduledBolusChain.confirm(chain, 620_000L, config)
        chain = ScheduledBolusChain.secondConfirm(chain, 630_000L, config)
        chain = ScheduledBolusChain.registerPermission(chain, 8801)
        chain = ScheduledBolusChain.onStartObservation(chain, started(8801, 700_000L))
        chain = ScheduledBolusChain.onTerminal(chain, 8801, ScheduledBolusChain.STATUS_COMPLETE)

        // 第二笔
        chain = ScheduledBolusChain.tick(chain, 1_700_000L, config)
        chain = ScheduledBolusChain.confirm(chain, 1_701_000L, config)
        chain = ScheduledBolusChain.secondConfirm(chain, 1_702_000L, config)
        chain = ScheduledBolusChain.registerPermission(chain, 8802)
        chain = ScheduledBolusChain.onStartObservation(chain, started(8802, 1_800_000L))
        chain = ScheduledBolusChain.onTerminal(chain, 8802, ScheduledBolusChain.STATUS_COMPLETE)

        assertEquals(ScheduledBolusChain.State.DONE, chain.state)
        assertTrue(chain.isTerminal)
        assertEquals(0, chain.remainingCount)
    }

    @Test
    fun `missed chain only reconciles to aborted and never to alerting`() {
        val missed = ScheduledBolusChain.reconcileMissed(chainOfTwo(), 10_000_000L, config)
        assertEquals(ScheduledBolusChain.State.CHAIN_ABORTED, missed.state)
        assertEquals(ScheduledBolusChain.AbortReason.MISSED, missed.abortReason)
    }

    @Test
    fun `editing the dose returns to alerting and marks it edited`() {
        val alerting = ScheduledBolusChain.tick(chainOfTwo(), 610_000L, config)
        val edited = ScheduledBolusChain.editDose(alerting, 3_000L)

        assertEquals(ScheduledBolusChain.State.ALERTING, edited.state)
        assertEquals(3_000L, edited.currentDoseMilliunits)
        assertTrue(edited.doseEdited)
    }
}
