package com.pumppan.controlx2.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 笔级状态机的回归测试。数据全部取自 2026-11-12 两笔真机取证（§19.2.1）。
 *
 * 这三个场景是设计里被实测推翻过的假设，必须逐条钉住：
 * 1. 一笔 6 U 会来 57 条 `CurrentBolusStatusResponse` → 只能出 1 条；
 * 2. 输注期间会回放上一笔的 `COMPLETE` → 不能把新笔误收；
 * 3. 终态先到 V1（无 requestedVolume）再到 V2 → 不能丢量、不能重复建条目。
 */
class BolusEpisodeTrackerTest {

    private fun tracker() = BolusEpisodeTracker()

    @Test
    fun `start observer fires once per bolus and never for the empty state`() {
        val observations = mutableListOf<BolusStartObservation>()
        val tracker = BolusEpisodeTracker(onStart = { observations.add(it) })
        var t = 1_000L

        tracker.onCurrentBolusStatus(0, 0, 0L, t)
        repeat(57) { index ->
            tracker.onCurrentBolusStatus(if (index == 0) 2 else 1, 6941, 6_000L, t)
            t += 3_000L
        }

        assertEquals(1, observations.size)
        assertEquals(6941, observations.single().bolusId)
        assertEquals(BolusLedgerPhase.REQUESTING, observations.single().phase)
    }

    @Test
    fun `57 polling samples of one bolus collapse to a single entry`() {
        val tracker = tracker()
        var t = 1_000L

        // 19:17:49 REQUESTING
        tracker.onCurrentBolusStatus(2, 6941, 6_000L, t)
        // 19:18:01 起 57 次轮询，1 s ~ 10 s 不规则
        repeat(56) {
            t += 3_000L
            tracker.onCurrentBolusStatus(1, 6941, 6_000L, t)
        }
        // 19:20:35 V1
        t += 20_000L
        tracker.onLastBolusStatus(6941, 3, 6_000L, null, 0L, t)

        val snapshot = tracker.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals(1, snapshot.count { it.bolusId == 6941 })
        assertEquals(BolusLedgerPhase.FINISHED, snapshot[0].phase)
        assertEquals(6_000L, snapshot[0].deliveredMilliUnits)
    }

    @Test
    fun `stale complete of previous bolus does not close the current one`() {
        val tracker = tracker()
        var t = 1_000L

        // 上一笔 14 U 收尾（19:30 前后）
        tracker.onCurrentBolusStatus(1, 6939, 14_000L, t)
        t += 10_000L
        tracker.onLastBolusStatus(6939, 3, 14_000L, 14_000L, 0L, t)

        // 新一笔 6 U 已进入输注
        t += 60_000L
        tracker.onCurrentBolusStatus(1, 6941, 6_000L, t)
        t += 12_000L

        // 泵回放上一笔 6939 COMPLETE（实测在 19:18:01 / 19:18:40 / 19:19:20 出现三次）
        repeat(3) {
            t += 10_000L
            tracker.onLastBolusStatus(6939, 3, 14_000L, 14_000L, 0L, t)
        }

        val current = tracker.snapshot().first { it.bolusId == 6941 }
        assertTrue("陈旧终态不能把新笔收掉", current.isOngoing)
        assertEquals(BolusLedgerPhase.DELIVERING, current.phase)
        assertNull(current.terminalStatusId)

        val previous = tracker.snapshot().first { it.bolusId == 6939 }
        assertEquals(BolusLedgerPhase.FINISHED, previous.phase)
        assertEquals(14_000L, previous.deliveredMilliUnits)
    }

    @Test
    fun `v1 then v2 fills requested volume without duplicating the entry`() {
        val tracker = tracker()
        var t = 1_000L

        // 模拟 App 在开笔后才启动：先只看到 V1（无 requestedVolume）。
        tracker.onLastBolusStatus(6941, 3, 6_000L, null, 0L, t)
        assertEquals(BolusLedgerPhase.FINISHED, tracker.snapshot()[0].phase)
        assertNull(tracker.snapshot()[0].requestedMilliUnits)

        t += 32_000L
        // V2：补齐 requestedVolume
        tracker.onLastBolusStatus(6941, 3, 6_000L, 6_000L, 0L, t)

        val entry = tracker.snapshot().single()
        assertEquals(1, tracker.snapshot().size)
        assertEquals(6_000L, entry.requestedMilliUnits)
        assertEquals(6_000L, entry.deliveredMilliUnits)
        assertEquals(1, entry.repeatCount)
    }

    @Test
    fun `empty state holds until grace expires then times out and accepts a late terminal`() {
        val tracker = BolusEpisodeTracker(terminalGraceMs = 60_000L)
        var t = 1_000L

        tracker.onCurrentBolusStatus(1, 6941, 6_000L, t)
        t += 30_000L
        // 空态开始（19:20:15）——不是中止证据
        tracker.onCurrentBolusStatus(0, 0, 0L, t)

        // 宽限期内仍算进行中
        t += 20_000L
        tracker.sweep(t)
        assertTrue(tracker.snapshot().single().isOngoing)

        // 宽限期满 → 未确认结束
        t += 45_000L
        tracker.sweep(t)
        assertEquals(BolusLedgerPhase.UNKNOWN, tracker.snapshot().single().phase)

        // 迟到终态回填同一笔
        t += 5_000L
        tracker.onLastBolusStatus(6941, 3, 6_000L, 6_000L, 0L, t)
        assertEquals(BolusLedgerPhase.FINISHED, tracker.snapshot().single().phase)
        assertEquals(3, tracker.snapshot().single().terminalStatusId)
    }

    @Test
    fun `ongoing entry is never evicted by max episodes`() {
        val tracker = BolusEpisodeTracker(maxEpisodes = 2)
        var t = 1_000L

        tracker.onCurrentBolusStatus(1, 5001, 1_000L, t)
        t += 1_000L
        tracker.onLastBolusStatus(5001, 3, 1_000L, 1_000L, 0L, t)
        t += 1_000L
        tracker.onCurrentBolusStatus(1, 5002, 2_000L, t)
        t += 1_000L
        tracker.onLastBolusStatus(5002, 3, 2_000L, 2_000L, 0L, t)
        t += 1_000L
        tracker.onCurrentBolusStatus(1, 5003, 3_000L, t)

        val snapshot = tracker.snapshot()
        assertTrue(snapshot.any { it.bolusId == 5003 && it.isOngoing })
        assertTrue(snapshot.size <= 2)
        assertFalse(snapshot.any { it.sortAtMillis > t })
    }

    @Test
    fun `finished entries older than 72 hours are purged while ongoing survives`() {
        val tracker = BolusEpisodeTracker(
            retentionMs = 72L * 60 * 60 * 1000,
            purgeIntervalMs = 30L * 60 * 1000,
        )
        var t = 1_000L

        tracker.onCurrentBolusStatus(1, 1001, 1_000L, t)
        t += 5_000L
        tracker.onLastBolusStatus(1001, 3, 1_000L, 1_000L, 0L, t)

        t += 1_000L
        tracker.onCurrentBolusStatus(1, 1002, 2_000L, t)

        // 73 小时后触发清理
        t += 73L * 60 * 60 * 1000
        tracker.sweep(t)

        val snapshot = tracker.snapshot()
        assertTrue(snapshot.any { it.bolusId == 1002 && it.isOngoing })
        assertFalse("超过 72 小时的已终态笔必须被丢弃", snapshot.any { it.bolusId == 1001 })
    }

    @Test
    fun `replayed terminal from an old session is ignored, recent one is adopted`() {
        val tracker = BolusEpisodeTracker(adoptTerminalWithinMs = 600_000L)

        // 启动时泵回放昨天的完成态：不允许变成一条「本次运行」的流水。
        tracker.onLastBolusStatus(
            bolusId = 6939,
            bolusStatusId = 3,
            deliveredMilliUnits = 14_000L,
            requestedMilliUnits = 14_000L,
            extendedDurationSec = 0L,
            now = 1_000L,
            reportedAtMillis = 1_000L - 86_400_000L,
        )
        assertTrue(tracker.snapshot().isEmpty())

        // 刚结束、但没看到开笔（进程中途启动）：补录。
        tracker.onLastBolusStatus(
            bolusId = 6941,
            bolusStatusId = 3,
            deliveredMilliUnits = 6_000L,
            requestedMilliUnits = 6_000L,
            extendedDurationSec = 0L,
            now = 1_000L,
            reportedAtMillis = 1_000L - 60_000L,
        )
        assertEquals(1, tracker.snapshot().size)
    }

    @Test
    fun `terminal arriving without a start still produces one entry`() {
        val tracker = tracker()
        tracker.onLastBolusStatus(6941, 3, 6_000L, 6_000L, 0L, 1_000L)

        val entry = tracker.snapshot().single()
        assertEquals(BolusLedgerPhase.FINISHED, entry.phase)
        assertEquals(6_000L, entry.deliveredMilliUnits)
        assertFalse(entry.isOngoing)
    }
}
