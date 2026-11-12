package com.pumppan.controlx2.scheduled

import com.pumppan.controlx2.bolus.state.BolusLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预约计划的输入与双向换算（用户 2026-11-12 定的填写方式）：
 * 第一笔填绝对时刻 + 剂量；后续每笔可填「间隔」或「绝对时刻」，另一侧由软件算出。
 *
 * 示例：第一笔 12:00 8U，第二笔 12:20 4U（+20 min），第三笔 13:20 2U（+1 h）。
 */
class ScheduledPlanTest {

    private val x2 = BolusLimits.Default.copy(bolusIncrementMilliunits = 50)

    private val config = ScheduledBolusConfig(
        maxChainCount = 3,
        minLinkIntervalMs = 300_000L,
        maxLinkIntervalMs = 24 * 60 * 60 * 1000L,
    )

    private fun at(hour: Int, minute: Int): Long {
        // 固定一个日期，避免依赖当前时钟
        return java.time.LocalDateTime.of(2026, 11, 12, hour, minute)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    private fun samplePlan() = ScheduledPlan(
        firstAtMillis = at(12, 0),
        firstDoseMilliunits = 8_000L,
        links = listOf(
            ScheduledBolusChain.Link(4_000L, 0L, absoluteAtMillis = at(12, 20)),
            ScheduledBolusChain.Link(2_000L, 60 * 60 * 1000L),
        ),
    )

    @Test
    fun `absolute and interval inputs mix into one timeline`() {
        val points = samplePlan().timeline()
        assertEquals(3, points.size)
        assertEquals(at(12, 0), points[0].atMillis)
        assertEquals(at(12, 20), points[1].atMillis)
        assertEquals(20 * 60 * 1000L, points[1].intervalFromPreviousMs)
        assertEquals(at(13, 20), points[2].atMillis)
        assertEquals(60 * 60 * 1000L, points[2].intervalFromPreviousMs)
    }

    @Test
    fun `editing interval recomputes the absolute time`() {
        val plan = samplePlan().withInterval(0, 30 * 60 * 1000L)
        val points = plan.timeline()
        assertEquals(at(12, 30), points[1].atMillis)
        // 第三笔仍是「相对上一笔 +1h」→ 顺延到 13:30
        assertEquals(at(13, 30), points[2].atMillis)
    }

    @Test
    fun `editing absolute time recomputes the interval`() {
        val plan = samplePlan().withAbsoluteAt(1, at(13, 0))
        val points = plan.timeline()
        assertEquals(at(13, 0), points[2].atMillis)
        assertEquals(40 * 60 * 1000L, points[2].intervalFromPreviousMs)
    }

    @Test
    fun `moving the first bolus shifts relative links but not absolute ones`() {
        val plan = samplePlan().moveFirstTo(at(12, 10))
        val points = plan.timeline()
        // 第二笔是绝对 12:20 → 间隔缩到 10 分钟
        assertEquals(at(12, 20), points[1].atMillis)
        assertEquals(10 * 60 * 1000L, points[1].intervalFromPreviousMs)
        // 第三笔是相对 +1h → 跟随第二笔到 13:20
        assertEquals(at(13, 20), points[2].atMillis)
    }

    @Test
    fun `valid plan has no issues`() {
        val issues = samplePlan().validate(config, x2, now = at(11, 0))
        assertTrue(issues.toString(), issues.isEmpty())
    }

    @Test
    fun `chain count includes the first bolus`() {
        val plan = ScheduledPlan(
            firstAtMillis = at(12, 0),
            firstDoseMilliunits = 1_000L,
            links = listOf(
                ScheduledBolusChain.Link(1_000L, 600_000L),
                ScheduledBolusChain.Link(1_000L, 600_000L),
                ScheduledBolusChain.Link(1_000L, 600_000L),
            ),
        )
        val issues = plan.validate(config, x2, now = at(11, 0))
        assertTrue(issues.any { it is PlanIssue.TooManyLinks })
    }

    @Test
    fun `dose must respect max and pump increment`() {
        val tooBig = samplePlan().withDose(0, 30_000L)
        assertTrue(tooBig.validate(config, x2, now = at(11, 0)).any { it is PlanIssue.DoseOutOfRange })

        val offGrid = samplePlan().withDose(1, 4_025L)
        assertTrue(offGrid.validate(config, x2, now = at(11, 0)).any { it is PlanIssue.DoseOffIncrement })
    }

    @Test
    fun `intervals outside the configured window are rejected`() {
        val tooShort = samplePlan().withInterval(0, 60_000L)
        assertTrue(tooShort.validate(config, x2, now = at(11, 0)).any { it is PlanIssue.IntervalTooShort })

        val tooLong = samplePlan().withInterval(0, 30L * 60 * 60 * 1000)
        assertTrue(tooLong.validate(config, x2, now = at(11, 0)).any { it is PlanIssue.IntervalTooLong })
    }

    @Test
    fun `plan in the past is rejected`() {
        val issues = samplePlan().validate(config, x2, now = at(13, 0))
        assertTrue(issues.any { it is PlanIssue.FirstTimeInPast })
    }
}
