package com.pumppan.controlx2.bolus.state

import androidx.compose.runtime.saveable.Saver

/** v2 三步：剂量与形状 → 校验与确认 → 结局（原「入口选择」步已取消，流程从输入页开始）。 */
enum class BolusFlowStep { SHAPE, CONFIRM, RESULT }

/**
 * 三步流程的唯一状态源（纯数据，可单测）。
 *
 * - 剂量一律 milliunit 整型，UI 只做「一位小数」的显示与解析。
 * - 形状切换时用 [BolusForm.splitByPercent] 反算；切回普通形状时把延后段并入立即段。
 * - 校验永远调用 [BolusForm.validate]，不复制第二份规则。
 */
data class BolusFlowState(
    val step: BolusFlowStep = BolusFlowStep.SHAPE,
    val source: BolusSource = BolusSource.MANUAL,
    val shape: BolusShape = BolusShape.NORMAL,
    val nowMilliunits: Int = 0,
    val laterMilliunits: Int = 0,
    val extendedSeconds: Int = 0,
    /** 普通/方波/双波切换时立即段占比；普通形状下不参与计算。 */
    val percent: Int = DEFAULT_PERCENT,
    /** 向导交接过来的计算依据；手工 / 预设路径为 null。 */
    val calc: BolusCalcPayload? = null,
    /** 向导算出的建议剂量；用户改写后与 [nowMilliunits] 对照显示。手工 / 预设路径为 null。 */
    val suggestedMilliunits: Int? = null,
) {
    val totalMilliunits: Int get() = nowMilliunits + laterMilliunits

    fun draft(): BolusDraft = BolusDraft(
        nowMilliunits = nowMilliunits,
        laterMilliunits = laterMilliunits,
        extendedSeconds = extendedSeconds,
        shape = shape,
        source = source,
        calc = calc,
    )

    /** 来源由入口标签决定（手工 / 预设 / 向导），只改来源、不改变当前相位。 */
    fun withSource(source: BolusSource): BolusFlowState = copy(source = source)

    fun withNow(milliunits: Int): BolusFlowState = copy(nowMilliunits = milliunits.coerceAtLeast(0))

    fun withLater(milliunits: Int): BolusFlowState = copy(laterMilliunits = milliunits.coerceAtLeast(0))

    fun withDurationSeconds(seconds: Int): BolusFlowState = copy(extendedSeconds = seconds.coerceAtLeast(0))

    /** 改总量：普通形状直接改立即段；扩展形状按当前占比重新拆分。 */
    fun withTotal(totalMilliunits: Int, limits: BolusLimits = BolusLimits.Default): BolusFlowState {
        val safe = totalMilliunits.coerceAtLeast(0)
        if (shape == BolusShape.NORMAL) return withNow(safe)
        val split = BolusForm.splitByPercent(safe, percent, limits.bolusIncrementMilliunits)
        return copy(nowMilliunits = split.nowMilliunits, laterMilliunits = split.laterMilliunits)
    }

    fun withPercent(percent: Int, limits: BolusLimits = BolusLimits.Default): BolusFlowState {
        val safe = percent.coerceIn(0, 100)
        if (shape == BolusShape.NORMAL) return copy(percent = safe)
        val split = BolusForm.splitByPercent(
            totalMilliunits,
            safe,
            limits.bolusIncrementMilliunits,
        )
        return copy(percent = safe, nowMilliunits = split.nowMilliunits, laterMilliunits = split.laterMilliunits)
    }

    /**
     * 形状切换：普通 → 方波/双波按占比拆分；方波/双波 → 普通把延后段并入立即段并清空时长。
     * 方波与双波之间切换只改标签，保留当前数值。
     */
    fun withShape(newShape: BolusShape, limits: BolusLimits = BolusLimits.Default): BolusFlowState {
        if (newShape == shape) return this
        if (newShape == BolusShape.NORMAL) {
            return copy(
                shape = newShape,
                nowMilliunits = totalMilliunits,
                laterMilliunits = 0,
                extendedSeconds = 0,
            )
        }
        if (shape == BolusShape.NORMAL) {
            val split = BolusForm.splitByPercent(
                totalMilliunits,
                percent,
                limits.bolusIncrementMilliunits,
            )
            return copy(
                shape = newShape,
                nowMilliunits = split.nowMilliunits,
                laterMilliunits = split.laterMilliunits,
                extendedSeconds = if (extendedSeconds > 0) extendedSeconds else DEFAULT_EXTENDED_SECONDS,
            )
        }
        return copy(shape = newShape)
    }

    /** 「扩展大剂量」开关：开 → 双波（立即 + 延后），关 → 普通并合并数值。 */
    fun withExtended(enabled: Boolean, limits: BolusLimits = BolusLimits.Default): BolusFlowState =
        withShape(if (enabled) BolusShape.DUAL else BolusShape.NORMAL, limits)

    fun validationErrors(
        ciqEnabled: Boolean?,
        limits: BolusLimits = BolusLimits.Default,
        ciqVersion: CiqVersion? = null,
    ): List<BolusValidationError> = BolusForm.validate(draft(), ciqEnabled, limits, ciqVersion)

    /** 前进一格；剂量步骤校验不过则原地返回错误，不推进。 */
    fun advance(
        ciqEnabled: Boolean?,
        limits: BolusLimits = BolusLimits.Default,
        ciqVersion: CiqVersion? = null,
    ): BolusAdvance = when (step) {
        BolusFlowStep.SHAPE -> {
            val errors = validationErrors(ciqEnabled, limits, ciqVersion)
            if (errors.isEmpty()) BolusAdvance.Ok(copy(step = BolusFlowStep.CONFIRM))
            else BolusAdvance.Invalid(errors)
        }
        BolusFlowStep.CONFIRM -> BolusAdvance.Ok(copy(step = BolusFlowStep.RESULT))
        BolusFlowStep.RESULT -> BolusAdvance.Ok(this)
    }

    /** 后退一格；输入页没有上一步，退出流程由页面负责（回到来源标签）。 */
    fun back(): BolusFlowState = when (step) {
        BolusFlowStep.SHAPE -> this
        BolusFlowStep.CONFIRM -> copy(step = BolusFlowStep.SHAPE)
        BolusFlowStep.RESULT -> copy(step = BolusFlowStep.CONFIRM)
    }

    companion object {
        const val DEFAULT_PERCENT = 50
        /** 默认 50/50、2 小时（7200 秒）；UI 以分钟输入。 */
        const val DEFAULT_EXTENDED_SECONDS = 7_200

        /** rememberSaveable 的 Saver：旋转后草稿原样恢复（纯数据、无泵依赖）。 */
        val Saver: Saver<BolusFlowState, List<Any>> = Saver(
            save = { state ->
                listOf(
                    state.step.name,
                    state.source.name,
                    state.shape.name,
                    state.nowMilliunits,
                    state.laterMilliunits,
                    state.extendedSeconds,
                    state.percent,
                    state.calc?.let { calc ->
                        listOf(
                            calc.foodMilliunits,
                            calc.correctionMilliunits,
                            calc.carbsGrams,
                            // 用 -1 表示「没有血糖」，避免把 null 塞进 saveable 列表。
                            calc.bgMgdl ?: -1,
                            calc.iobMilliunits,
                        )
                    } ?: emptyList<Int>(),
                    // 用 -1 表示「建议值未改写」。
                    state.suggestedMilliunits ?: -1,
                )
            },
            restore = { saved ->
                val rawCalc = saved.getOrNull(7) as? List<*>
                val calc = rawCalc
                    ?.takeIf { it.size == 5 }
                    ?.let {
                        BolusCalcPayload(
                            foodMilliunits = it[0] as Int,
                            correctionMilliunits = it[1] as Int,
                            carbsGrams = it[2] as Int,
                            bgMgdl = (it[3] as Int).takeIf { bg -> bg >= 0 },
                            iobMilliunits = it[4] as Int,
                        )
                    }
                BolusFlowState(
                    step = BolusFlowStep.valueOf(saved[0] as String),
                    source = BolusSource.valueOf(saved[1] as String),
                    shape = BolusShape.valueOf(saved[2] as String),
                    nowMilliunits = saved[3] as Int,
                    laterMilliunits = saved[4] as Int,
                    extendedSeconds = saved[5] as Int,
                    percent = saved[6] as Int,
                    calc = calc,
                    suggestedMilliunits = (saved.getOrNull(8) as? Int)?.takeIf { it >= 0 },
                )
            },
        )
    }
}

sealed interface BolusAdvance {
    data class Ok(val state: BolusFlowState) : BolusAdvance
    data class Invalid(val errors: List<BolusValidationError>) : BolusAdvance
}
