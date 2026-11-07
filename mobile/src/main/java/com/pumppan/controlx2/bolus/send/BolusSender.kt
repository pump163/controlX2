package com.pumppan.controlx2.bolus.send

import com.jwoglom.pumpx2.pump.messages.request.control.InitiateBolusRequest
import com.pumppan.controlx2.bolus.state.BolusDraft
import com.pumppan.controlx2.bolus.state.BolusForm
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.BolusShape
import com.pumppan.controlx2.bolus.state.BolusValidationError
import com.pumppan.controlx2.bolus.state.CiqVersion
import com.pumppan.controlx2.bolus.state.PumpCapabilityProfile
import com.pumppan.controlx2.bolus.state.canStartRemoteExtended

/**
 * 一次大剂量的 wire 计划（S6 发送层）。
 *
 * `totalVolume` 对应 `-98` 的立即段，`extendedVolume` / `extendedSeconds` / `extended3`
 * 只在型号能力允许时非零。X2 的扩展三字段恒为 0（固件忽略，真机 6851/6852 定论）。
 */
data class BolusSendPlan(
    val modelKey: String,
    /** wire totalVolume：立即段（毫单位）。 */
    val totalVolumeMilliunits: Int,
    val extendedVolumeMilliunits: Int,
    val extendedSeconds: Int,
    val bolusTypeBitmask: Int,
    /** 该型号的延展能力是否已真机验证（false = 实验特性，需写取证日志）。 */
    val extendedVerified: Boolean,
    /** 用于取证日志的能力快照。 */
    val remoteExtendedBolusStart: Boolean,
    val minImmediateMilliunits: Int,
    /** 客户端兜底校验值（不是泵下限），仅用于取证日志的能力快照。 */
    val clientMinExtendedCheckMilliunits: Int,
    val maxExtendedSeconds: Int,
) {
    /** 是否真的填了延展三字段（决定走 11 参还是 8 参构造器）。 */
    val usesExtendedFields: Boolean
        get() = extendedVolumeMilliunits > 0 || extendedSeconds > 0
}

sealed interface BolusPrepareResult {
    data class Ready(val plan: BolusSendPlan) : BolusPrepareResult

    data class Blocked(val errors: List<BolusValidationError>) : BolusPrepareResult
}

/**
 * S6 发送层的纯逻辑部分：**发送前**再查一次型号能力（不依赖按钮置灰），
 * 然后把 UI 草稿翻译成 `-98` 的 wire 字段。
 *
 * - 能力闸门唯一来自 [PumpCapabilities]，本类不写 `if (isX2)`。
 * - X2：扩展路径直接 Blocked（`remoteExtendedBolusStart=false`），只能走 8 参普通大剂量。
 * - Mobi：能力允许且填了延展段时走 11 参，`totalVolume` 可为 0（纯方波）。
 * - 协议常量（8/11 参构造器、`Validate` 阈值）一律复用 pumpx2，本文件不复制。
 */
object BolusSender {

    /**
     * 构造发送计划。返回 [BolusPrepareResult.Blocked] 时调用方**不得**发送任何报文。
     */
    fun prepare(
        draft: BolusDraft,
        ciqEnabled: Boolean?,
        limits: BolusLimits,
        profile: PumpCapabilityProfile,
        ciqVersion: CiqVersion? = null,
    ): BolusPrepareResult {
        // 发送前的二次能力校验：延展形状必须有配置授权。
        if (draft.shape != BolusShape.NORMAL && !limits.canStartRemoteExtended()) {
            return BolusPrepareResult.Blocked(listOf(BolusValidationError.REMOTE_EXTENDED_NOT_SUPPORTED))
        }
        val errors = BolusForm.validate(draft, ciqEnabled, limits, ciqVersion)
        if (errors.isNotEmpty()) return BolusPrepareResult.Blocked(errors)

        val extended = draft.shape != BolusShape.NORMAL
        // 延展掩码用配置里的实测值（Mobi=12），普通大剂量沿用既有 FOOD2 行为。
        val bitmask =
            if (extended) profile.extendedBitmask
            else profile.requestBitmask["FOOD2"] ?: DEFAULT_FOOD2_BITMASK

        return BolusPrepareResult.Ready(
            BolusSendPlan(
                modelKey = profile.modelKey,
                // 扩展形状的立即段可以合法为 0（Mobi 纯方波）；普通形状即总量。
                totalVolumeMilliunits = draft.nowMilliunits,
                extendedVolumeMilliunits = if (extended) draft.laterMilliunits else 0,
                extendedSeconds = if (extended) draft.extendedSeconds else 0,
                bolusTypeBitmask = bitmask,
                extendedVerified = profile.remoteExtendedBolusVerified,
                remoteExtendedBolusStart = profile.remoteExtendedBolusStart,
                minImmediateMilliunits = profile.minImmediateMilliunits,
                clientMinExtendedCheckMilliunits = profile.clientMinExtendedCheckMilliunits,
                maxExtendedSeconds = profile.maxExtendedSeconds(ciqEnabled),
            ),
        )
    }

    /**
     * 把计划翻译成 `-98` 请求。
     *
     * - 无延展段 → 8 参构造器（内部把延展三字段写 0）。
     * - 有延展段 → 11 参构造器（`total=0 + extended>=400` 也能通过 pumpx2 的 Validate）。
     *
     * 构造器拒绝（如 bolusId<=0、金额越界）时返回 null，调用方按「未发送」处理。
     */
    fun buildRequest(
        bolusId: Int,
        plan: BolusSendPlan,
    ): InitiateBolusRequest? = runCatching {
        if (plan.usesExtendedFields) {
            InitiateBolusRequest(
                plan.totalVolumeMilliunits.toLong(),
                bolusId,
                plan.bolusTypeBitmask,
                0L, // foodVolume
                0L, // correctionVolume
                0, // carbs
                0, // bg
                0L, // iob
                plan.extendedVolumeMilliunits.toLong(),
                plan.extendedSeconds.toLong(),
                0L, // extended3：未验证字段，恒 0
            )
        } else {
            InitiateBolusRequest(
                plan.totalVolumeMilliunits.toLong(),
                bolusId,
                plan.bolusTypeBitmask,
                0L,
                0L,
                0,
                0,
                0L,
            )
        }
    }.getOrNull()

    /** 配置缺失时的兜底位；与既有 `BolusDeliveryHistoryLog.BolusType.FOOD2` 行为一致。 */
    private const val DEFAULT_FOOD2_BITMASK = 8
}
