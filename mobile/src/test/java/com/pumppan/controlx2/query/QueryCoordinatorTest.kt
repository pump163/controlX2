package com.pumppan.controlx2.query

import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ActiveAamBitsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第 4 步：协调器的到期判定、合并、断连冻结与影子模式。 */
class QueryCoordinatorTest {

    private class RecordingSender : PumpRequestSender {
        val sent = mutableListOf<Message>()

        override fun send(type: SendType, messages: List<Message>): Boolean {
            sent.addAll(messages)
            return true
        }
    }

    private fun topic(
        interval: Long = 60_000,
        floor: Long? = 60_000,
        priority: String = "P0_FLOOR",
    ) = TopicPolicy(
        intervalMs = interval,
        minIntervalMs = 15_000,
        priority = priority,
        onlyWhenVisible = false,
        maxWindowMs = null,
        floorIntervalMs = floor,
        floorPriority = if (floor != null) "P0_FLOOR" else null,
    )

    private val topics = linkedMapOf(
        "PUMP_VITALS" to topic(),
        "IOB" to topic(),
        "PUMP_STATE" to topic(60_000, null, "P1_STATE"),
        "BOLUS_OUTCOME" to topic(60_000, null, "P0_CONFIRM"),
        "DASHBOARD_BATCH" to topic(60_000, null, "P1_STATE"),
    )

    private fun policyOf(topics: Map<String, TopicPolicy>) = QueryPolicy(
        schemaVersion = 2,
        source = PolicySource.ASSET,
        hash = "test",
        loadedAtMillis = 0L,
        topics = topics,
        freshness = FreshnessPolicy(30_000, 180_000, 900_000),
        states = StatesPolicy(8_000, 30_000, 1_000, true, true, 10_000, 300_000),
        budget = BudgetPolicy(
            perMinute = 10,
            burst = 20,
            burstWindowMs = 60_000,
            degradeOrder = listOf("P2_DECORATIVE", "P1_STATE"),
            protectPriority = listOf("P0_FLOOR", "P0_CONFIRM"),
        ),
        events = emptyMap(),
        reconnect = ReconnectPolicy(listOf("PUMP_VITALS"), 3_000, 15_000, 5_000, 2),
        switches = SwitchesPolicy(true, true, true, true),
        diagnostics = PolicyDiagnostics(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        ),
        applyPendingOn = emptyList(),
    )

    private fun healthyPort(): PumpCommandPort {
        val port = PumpCommandPort(RecordingSender(), linkSilentAfterMs = 30_000L, maxInFlight = 10)
        // 喂一条回包让 linkState = OK（lastResponseAt 用 0 表示「从未收到」，所以时间从 1000 起）
        port.onMessage(ActiveAamBitsRequest(byteArrayOf(2)), now = 1_000)
        return port
    }

    private fun response() = ActiveAamBitsRequest(byteArrayOf(2))

    @Test
    fun shadowPlansWithoutSending() {
        val sender = RecordingSender()
        val port = PumpCommandPort(sender, linkSilentAfterMs = 30_000L, maxInFlight = 10)
        port.onMessage(response(), now = 1_000)
        val coordinator = QueryCoordinator(policyOf(topics), port, mode = QueryCoordinator.Mode.SHADOW)

        val plan = coordinator.tick(now = 1_000)

        assertEquals(listOf("PUMP_VITALS", "IOB"), plan.domains)
        assertEquals(4, plan.requestCount)
        assertEquals(0, plan.sent)
        assertTrue("shadow mode must not touch the wire: ${sender.sent}", sender.sent.isEmpty())
    }

    @Test
    fun activeSendsOnceThenWaitsForInterval() {
        val sender = RecordingSender()
        val port = PumpCommandPort(sender, linkSilentAfterMs = 30_000L, maxInFlight = 10)
        port.onMessage(response(), now = 1_000)
        val coordinator = QueryCoordinator(policyOf(topics), port, mode = QueryCoordinator.Mode.ACTIVE)

        val first = coordinator.tick(now = 1_000)
        assertEquals(4, first.sent)
        assertEquals(4, sender.sent.size)

        val tooEarly = coordinator.tick(now = 31_000)
        assertTrue(tooEarly.domains.isEmpty())
        assertEquals(4, sender.sent.size)

        val nextRound = coordinator.tick(now = 61_000)
        assertEquals(4, nextRound.sent)
        assertEquals(8, sender.sent.size)
    }

    @Test
    fun linkDownFreezesAndResumesImmediately() {
        val sender = RecordingSender()
        val port = PumpCommandPort(sender, linkSilentAfterMs = 30_000L, maxInFlight = 10)
        port.onMessage(response(), now = 1_000)
        val coordinator = QueryCoordinator(policyOf(topics), port, mode = QueryCoordinator.Mode.ACTIVE)

        coordinator.tick(now = 1_000)
        assertEquals(4, sender.sent.size)

        port.markLinkDown()
        val frozen = coordinator.tick(now = 61_000)
        assertTrue(frozen.domains.isEmpty())
        assertEquals(QueryCoordinator.SkipReason.LINK_DOWN, frozen.skipped["PUMP_VITALS"])
        assertEquals(4, sender.sent.size)

        // 恢复：不需要等下一个周期，下一次 tick 立即补
        port.markLinkUp()
        port.onMessage(response(), now = 62_000)
        val resumed = coordinator.tick(now = 62_000)
        assertEquals(listOf("PUMP_VITALS", "IOB"), resumed.domains)
        assertEquals(4, resumed.sent)
    }

    @Test
    fun suspectLinkOnlyAllowsTopicsWithFloor() {
        val sender = RecordingSender()
        val port = PumpCommandPort(sender, linkSilentAfterMs = 30_000L, maxInFlight = 10)
        // 只有一条很久以前的回包 → 静默超时，linkState = SUSPECT
        port.onMessage(response(), now = 1_000)
        val coordinator = QueryCoordinator(
            policyOf(topics), port, mode = QueryCoordinator.Mode.ACTIVE,
            activeDomains = setOf("PUMP_VITALS", "IOB", "PUMP_STATE", "BOLUS_OUTCOME"),
        )

        val plan = coordinator.tick(now = 61_000)

        // 带保底的两域放行；PUMP_STATE / BOLUS_OUTCOME 被跳过
        assertEquals(listOf("PUMP_VITALS", "IOB"), plan.domains)
        assertEquals(QueryCoordinator.SkipReason.LINK_SUSPECT, plan.skipped["PUMP_STATE"])
        assertEquals(QueryCoordinator.SkipReason.LINK_SUSPECT, plan.skipped["BOLUS_OUTCOME"])
    }

    @Test
    fun pageBatchesWithoutRequestListAreSkipped() {
        val coordinator = QueryCoordinator(policyOf(topics), healthyPort())

        val plan = coordinator.tick(now = 1_000)

        assertEquals(QueryCoordinator.SkipReason.NOT_MIGRATED, plan.skipped["DASHBOARD_BATCH"])
        assertTrue("DASHBOARD_BATCH" !in plan.domains)
    }

    @Test
    fun windowDomainStaysClosedUntilOpened() {
        val sender = RecordingSender()
        val port = PumpCommandPort(sender, linkSilentAfterMs = 30_000L, maxInFlight = 10)
        port.onMessage(response(), now = 1_000)
        val windowed = topics.toMutableMap().apply {
            put(
                "BOLUS_OUTCOME",
                topic(1_000, null, "P0_CONFIRM").copy(maxWindowMs = 60_000),
            )
        }
        val coordinator = QueryCoordinator(
            policyOf(windowed), port, mode = QueryCoordinator.Mode.ACTIVE,
            activeDomains = setOf("PUMP_VITALS", "BOLUS_OUTCOME"),
            dispatchedDomains = setOf("BOLUS_OUTCOME"),
        )

        // 窗口没开：一条都不发，原因必须可读
        val closed = coordinator.tick(now = 2_000)
        assertEquals(QueryCoordinator.SkipReason.WINDOW_CLOSED, closed.skipped["BOLUS_OUTCOME"])
        assertEquals(0, closed.sent)

        coordinator.openWindow("BOLUS_OUTCOME", durationMs = 60_000, now = 2_000)
        val opened = coordinator.tick(now = 3_000)
        assertTrue("BOLUS_OUTCOME" in opened.domains)
        assertEquals(1, opened.sent)
        // 已接管但未下发的域留在计划里（可见），但不产生任何报文
        assertEquals(1, sender.sent.size)

        // 到期即停，不需要调用方显式关闭
        assertTrue(!coordinator.isWindowOpen("BOLUS_OUTCOME", now = 62_001))
        val expired = coordinator.tick(now = 62_001)
        assertEquals(QueryCoordinator.SkipReason.WINDOW_CLOSED, expired.skipped["BOLUS_OUTCOME"])
    }

    @Test
    fun dispatchedSubsetDoesNotSendOtherActiveDomains() {
        val sender = RecordingSender()
        val port = PumpCommandPort(sender, linkSilentAfterMs = 30_000L, maxInFlight = 10)
        port.onMessage(response(), now = 1_000)
        val coordinator = QueryCoordinator(
            policyOf(topics), port, mode = QueryCoordinator.Mode.ACTIVE,
            activeDomains = setOf("PUMP_VITALS", "IOB"),
            dispatchedDomains = setOf("IOB"),
        )

        val plan = coordinator.tick(now = 61_000)

        assertEquals(1, plan.sent)
        assertEquals(1, sender.sent.size) // 只有 IOB 真发；PUMP_VITALS 只推进计时
    }

    @Test
    fun nextDueReportsRemainingTime() {
        val port = healthyPort()
        val coordinator = QueryCoordinator(policyOf(topics), port, mode = QueryCoordinator.Mode.ACTIVE)

        coordinator.tick(now = 1_000)
        val due = coordinator.nextDueIn(now = 31_000)

        assertEquals(30_000L, due["PUMP_VITALS"])
        assertEquals(30_000L, due["IOB"])
    }

    @Test
    fun entryKickSendsEvenWhenIntervalNotDue() {
        val sender = RecordingSender()
        val port = PumpCommandPort(sender, linkSilentAfterMs = 30_000L, maxInFlight = 10)
        port.onMessage(response(), now = 1_000)
        val coordinator = QueryCoordinator(policyOf(topics), port, mode = QueryCoordinator.Mode.ACTIVE)

        // 没有任何一拍周期到账，kick 也必须自己发出去
        val sent = coordinator.requestNow("IOB", now = 2_000)

        assertEquals(1, sent)
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun entryKickCoalescesRepeatedCalls() {
        val sender = RecordingSender()
        val port = PumpCommandPort(sender, linkSilentAfterMs = 30_000L, maxInFlight = 10)
        port.onMessage(response(), now = 1_000)
        val coordinator = QueryCoordinator(policyOf(topics), port, mode = QueryCoordinator.Mode.ACTIVE)

        assertEquals(1, coordinator.requestNow("IOB", now = 2_000))
        // 2s 合并窗内的第二脚不再发（连点标签、进页 + 首次组合叠加都压成一拍）
        assertEquals(0, coordinator.requestNow("IOB", now = 2_100))
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun entryKickWhileLinkDownIsFlushedByNextTick() {
        val sender = RecordingSender()
        val port = PumpCommandPort(sender, linkSilentAfterMs = 30_000L, maxInFlight = 10)
        port.onMessage(response(), now = 1_000)
        val coordinator = QueryCoordinator(policyOf(topics), port, mode = QueryCoordinator.Mode.ACTIVE)

        coordinator.tick(now = 1_000)
        // 基线那一拍留下的在飞登记先清掉，本测只关心 kick 的补发路径
        clearInFlightOf(port, "PUMP_VITALS")
        clearInFlightOf(port, "IOB")

        port.markLinkDown()
        // 链路没就绪：不空发，只登记一次补发意图
        assertEquals(0, coordinator.requestNow("IOB", now = 5_000))
        assertEquals(4, sender.sent.size)

        port.markLinkUp()
        port.onMessage(response(), now = 20_000)
        // IOB 周期上还没到期（1000 + 60000），这里能发只可能来自补发意图；
        // 时间避开发送侧的 15s 节流窗（上一发在 1000）。
        val plan = coordinator.tick(now = 20_000)

        assertEquals(listOf("IOB"), plan.domains)
        assertEquals(1, plan.sent)
        assertEquals(5, sender.sent.size)
    }

    private fun clearInFlightOf(port: PumpCommandPort, domain: String) {
        QueryDomainRequests.forDomain(domain).forEach { message ->
            // getRequestProps 对少数只有响应侧元数据的类型会抛 IllegalArgumentException，跳过即可
            runCatching { message.getRequestProps() }.getOrNull()?.let {
                port.clearInFlight(it.opCode.toInt() and 0xFF)
            }
        }
    }
}

