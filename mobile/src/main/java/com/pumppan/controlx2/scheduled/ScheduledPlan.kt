package com.pumppan.controlx2.scheduled

import com.pumppan.controlx2.bolus.state.BolusLimits

/**
 * 一次预约的输入形态（用户 2026-11-12 定）：
 *
 * **第一笔给绝对时刻 + 剂量；之后每一笔只给「相对上一笔的开始时刻」的间隔 + 剂量。**
 * 例：第一笔 12:00 8U；第二笔「+20 分钟」4U（12:20）；第三笔「+1 小时」2U（13:20）。
 *
 * 之所以用相对间隔而不是绝对时刻：链的倒数锚点必须来自「泵确认上一笔已开始」（R2），
 * 绝对时刻会与真实开始时间漂移；相对量在锚点确定后再换算成下一笔的 due。
 */
data class ScheduledPlan(
    /** 第一笔的计划时刻（仅用于提醒用户去做首笔；倒数锚点仍取真实开始证据）。 */
    val firstAtMillis: Long,
    val firstDoseMilliunits: Long,
    /** 后续每笔：剂量 + 相对上一笔开始时刻的间隔（或绝对时刻）。 */
    val links: List<ScheduledBolusChain.Link>,
) {
    /** 含首笔在内的总笔数。 */
    val totalCount: Int get() = 1 + links.size

    /** 含首笔在内的计划总量（毫单位）；只用于展示，不作为闸（R11 明确不加 U 数累计上限）。 */
    val totalDoseMilliunits: Long get() = firstDoseMilliunits + links.sumOf { it.doseMilliunits }

    /**
     * 把两种输入统一成「相对上一笔的间隔」：
     * - 填了间隔 → 原样返回；
     * - 填了绝对时刻 → 间隔 = 该时刻 − 上一笔的计划时刻。
     *
     * 运行时锚点取真实的上一笔开始证据，因此绝对时刻模式在「首笔打晚了」时仍按钟点走，
     * 间隔模式则按真实开始时刻顺延——这正是两种输入各自的语义。
     */
    fun resolvedLinks(): List<ScheduledBolusChain.Link> {
        var previousPlannedAt = firstAtMillis
        return links.map { link ->
            val resolved = if (link.absoluteAtMillis != null) {
                val delay = (link.absoluteAtMillis - previousPlannedAt).coerceAtLeast(0L)
                link.copy(delayAfterPreviousMs = delay)
            } else {
                link
            }
            previousPlannedAt = link.absoluteAtMillis ?: (previousPlannedAt + resolved.delayAfterPreviousMs)
            resolved
        }
    }

    // ---- 双向编辑：绝对时刻 ⇄ 间隔，界面只调这几个方法 ----

    /** 时间线上的一点；`linkIndex` = -1 表示首笔。 */
    data class Point(
        val linkIndex: Int,
        val atMillis: Long,
        val doseMilliunits: Long,
        val intervalFromPreviousMs: Long,
        val isAbsoluteInput: Boolean,
    )

    /** 按当前输入算出整条时间线；两种输入混填也能得到一致结果。 */
    fun timeline(): List<Point> {
        val resolved = resolvedLinks()
        val points = mutableListOf(
            Point(
                linkIndex = -1,
                atMillis = firstAtMillis,
                doseMilliunits = firstDoseMilliunits,
                intervalFromPreviousMs = 0,
                isAbsoluteInput = true,
            ),
        )
        var previous = firstAtMillis
        resolved.forEachIndexed { index, link ->
            val at = link.absoluteAtMillis ?: (previous + link.delayAfterPreviousMs)
            points += Point(
                linkIndex = index,
                atMillis = at,
                doseMilliunits = link.doseMilliunits,
                intervalFromPreviousMs = at - previous,
                isAbsoluteInput = link.absoluteAtMillis != null,
            )
            previous = at
        }
        return points
    }

    /** 改第一笔的时刻；相对间隔的笔随之平移，绝对时刻的笔保持钟点不动。 */
    fun moveFirstTo(atMillis: Long): ScheduledPlan = copy(firstAtMillis = atMillis)

    /** 改剂量：`linkIndex = -1` 为首笔。 */
    fun withDose(linkIndex: Int, doseMilliunits: Long): ScheduledPlan {
        if (linkIndex < 0) return copy(firstDoseMilliunits = doseMilliunits)
        val index = linkIndex
        if (index !in links.indices) return this
        val updated = links.toMutableList()
        updated[index] = updated[index].copy(doseMilliunits = doseMilliunits)
        return copy(links = updated)
    }

    /** 按「间隔」填：写入相对量，并清除该笔的绝对时刻标记。 */
    fun withInterval(linkIndex: Int, delayMs: Long): ScheduledPlan {
        val index = linkIndex
        if (index !in links.indices) return this
        val updated = links.toMutableList()
        updated[index] = updated[index].copy(
            delayAfterPreviousMs = delayMs,
            absoluteAtMillis = null,
        )
        return copy(links = updated)
    }

    /** 按「绝对时刻」填：只存钟点，间隔由 [timeline]/[resolvedLinks] 现算。 */
    fun withAbsoluteAt(linkIndex: Int, atMillis: Long): ScheduledPlan {
        val index = linkIndex
        if (index !in links.indices) return this
        val updated = links.toMutableList()
        val previous = timeline()[index].atMillis
        updated[index] = updated[index].copy(
            absoluteAtMillis = atMillis,
            delayAfterPreviousMs = (atMillis - previous).coerceAtLeast(0L),
        )
        return copy(links = updated)
    }
}

/** 计划校验结果的一项；UI 逐条映射成文案，本层不持有任何字符串资源。 */
sealed interface PlanIssue {
    /** 笔数（含首笔）超过 `maxChainCount`。 */
    data class TooManyLinks(val max: Int) : PlanIssue

    /** 笔数（含首笔）少于 `minChainLinks`；单笔请直接用普通大剂量。 */
    data class TooFewLinks(val min: Int) : PlanIssue

    /** 间隔为负（绝对时刻早于上一笔）。 */
    data class NegativeInterval(val step: Int) : PlanIssue

    /** 整链（含提前提醒与到点确认窗）越过当地自然日边界。 */
    data class CrossesMidnight(val step: Int) : PlanIssue

    /** 剂量越界；`step` 0 = 首笔，1..n = 后续第 n 笔。 */
    data class DoseOutOfRange(val step: Int) : PlanIssue

    /** 剂量不在泵的步进网格上（X2 = 0.05U）。 */
    data class DoseOffIncrement(val step: Int, val incrementMilliunits: Int) : PlanIssue

    /** 间隔短于下限；`step` 表示「第 step 笔相对上一笔」。 */
    data class IntervalTooShort(val step: Int, val minMs: Long) : PlanIssue

    /** 间隔长于上限。 */
    data class IntervalTooLong(val step: Int, val maxMs: Long) : PlanIssue

    /** 第一笔的时刻已经过去（预约必须指向未来）。 */
    data object FirstTimeInPast : PlanIssue

    /** 首笔剂量缺失（表单未填）。 */
    data object MissingFirstDose : PlanIssue
}

/**
 * 计划校验。**所有数值来自 [ScheduledBolusConfig] 与 [BolusLimits]**，不在这里写字面量。
 *
 * @param toleranceMs 允许「第一笔时刻刚刚过去」的容差，默认 0：预约只能指向未来。
 */
fun ScheduledPlan.validate(
    config: ScheduledBolusConfig,
    limits: BolusLimits,
    now: Long,
    toleranceMs: Long = 0L,
): List<PlanIssue> {
    val issues = mutableListOf<PlanIssue>()

    if (firstDoseMilliunits <= 0L) issues += PlanIssue.MissingFirstDose
    if (totalCount < config.minChainLinks) issues += PlanIssue.TooFewLinks(config.minChainLinks)
    if (totalCount > config.maxChainCount) issues += PlanIssue.TooManyLinks(config.maxChainCount)
    if (firstAtMillis < now - toleranceMs) issues += PlanIssue.FirstTimeInPast

    fun checkDose(step: Int, dose: Long) {
        if (dose < limits.minBolusMilliunits || dose > limits.maxBolusMilliunits) {
            issues += PlanIssue.DoseOutOfRange(step)
        } else if (limits.bolusIncrementMilliunits > 0 && dose % limits.bolusIncrementMilliunits != 0L) {
            issues += PlanIssue.DoseOffIncrement(step, limits.bolusIncrementMilliunits)
        }
    }

    checkDose(0, firstDoseMilliunits)
    // 用 timeline 的「实际差值」判断负间隔：绝对时刻早于上一笔时 resolvedLinks 会把
    // 间隔夹到 0，直接看 delayAfterPreviousMs 会漏掉这种情况。
    timeline().drop(1).forEachIndexed { index, point ->
        checkDose(index + 1, point.doseMilliunits)
        val interval = point.intervalFromPreviousMs
        if (interval < 0L) {
            issues += PlanIssue.NegativeInterval(index + 1)
        } else if (interval < config.minLinkIntervalMs) {
            issues += PlanIssue.IntervalTooShort(index + 1, config.minLinkIntervalMs)
        }
        if (interval > config.maxLinkIntervalMs) {
            issues += PlanIssue.IntervalTooLong(index + 1, config.maxLinkIntervalMs)
        }
    }

    // 不许跨天：提前提醒不能落到前一天，最后一笔的确认窗不能落到次日。
    val dayStart = firstAtMillis.toLocalDateStart()
    val dayEnd = dayStart + ONE_DAY_MS
    if (firstAtMillis - config.advanceNoticeMs < dayStart) {
        issues += PlanIssue.CrossesMidnight(0)
    }
    timeline().lastOrNull()?.let { last ->
        if (last.atMillis + config.confirmTimeoutMs > dayEnd) {
            issues += PlanIssue.CrossesMidnight(last.linkIndex + 1)
        }
    }
    return issues
}

private const val ONE_DAY_MS = 24L * 60 * 60 * 1000

private fun Long.toLocalDateStart(): Long {
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = this
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}
