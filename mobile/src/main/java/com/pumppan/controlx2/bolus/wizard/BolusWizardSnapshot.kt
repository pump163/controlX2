package com.pumppan.controlx2.bolus.wizard

import com.pumppan.controlx2.bolus.state.WizardConfig

/**
 * 输入快照层：一次取数把向导计算所需的全部输入固定下来，并给出**分项**时效判定。
 *
 * 口径（用户 2026-11-08 定）：
 * - **主动查询是主路径**：进向导 / 需要计算时先发一次查询；该次查询**之后到达**的读数才算
 *   `本次查询`。查询失败或超时才退回缓存。
 * - **IOB 只认 `ControlIQIOBResponse`**（App 里就是 `DataStore.iobUnits`），
 *   不接受 `BolusCalcDataSnapshotResponse` 自带的 iob 字段。
 * - **取值顺序**：先发一次真实查询；成功 → 用本次查询之后到达的 `ControlIQIOBResponse`。
 *   查询失败 / 超时才退回缓存，且缓存报文距现在不超过 `iobStaleAfterMs`（默认 **3 分钟**）才有效；
 *   超过 / 从未收到 → 「活性胰岛素本次不可用」，不显示、不扣减。
 * - **输注否决**：近 `recentInfusionVetoMillis`（默认 5 分钟）内存在新的输注记录
 *   （本机发送的，或泵历史里新增的），或泵真相层显示正在输注 / **延展进行中** →
 *   IOB 不可靠，**不扣减**，文案与「本次不可用」分开。
 *   - 实测衰减 −0.09～−0.11 U/分钟，3 分钟窗口对应约 **0.3 U**；若要求 0.2 U 容差，窗口需 ≤2 分钟。
 *   - 否决窗口的理由是方向性风险：那段时间的缓存值偏**低**，扣得少 → 建议剂量偏高。
 *   - 3U / 900s 方波实测 IOB 每 5 分钟整块 +1.00 U（泵自身记账粒度），延展期间实时值最多滞后约 1 U，
 *     比单纯「时间戳过期」更严重，所以延展必须由泵真相层（`47` / `-74`）判定，不能只看本机会话状态。
 * - 「泵每次查询会现算一个 IOB」目前是**待验证假设**（见设计文档），界面只能陈述
 *   「本次查询 / 缓存 · N 分钟前」，不得断言泵内部行为。
 * - `BolusCalcDataSnapshotResponse` 报文不带时间戳（已核实 1.9.0 报文成员），
 *   因此 `atMillis` 只能取本地收到时间。
 * - 血糖字段常驻：CGM 有实时值就预填（可改），没有就留空等用户输指血；不做 LastBG 回落，空 ≠ 0。
 * - 所有阈值来自 [WizardConfig]（`pump-capabilities.json`），本文件不写死。
 */
data class WizardGlucose(
    val mgdl: Int?,
    /** 读数**收到**的时间（本地时钟）；CGM 报文本身不带可直接信任的本地时间戳。 */
    val atMillis: Long?,
    val stale: Boolean,
) {
    /** 只有「有值且没过期」才算实时值；否则按没有处理。 */
    val available: Boolean get() = mgdl != null && !stale

    companion object {
        val Unavailable = WizardGlucose(null, null, false)
    }
}

/** 数据来源。界面必须照此标注，**本机推算不能写「泵」**。 */
enum class WizardDataOrigin { PUMP, LOCAL_ESTIMATE }

/**
 * IOB 的取得方式。界面只能陈述这两类**事实**：
 * - [CURRENT_QUERY]：这条读数是在我们最近一次查询**之后**到达的 → 标「本次查询」；
 * - [CACHE]：读数早于最近一次查询（或本次会话尚未查询）→ 标「缓存」。
 *
 * 不得据此断言「泵刚算了一遍」——泵内部行为尚未验证。
 */
enum class WizardIobSource { CURRENT_QUERY, CACHE }

/**
 * IOB 本次不可用的原因。**两条文案必须分开**，不要把「查不到」和「输注中」混成一句：
 * - [EXPIRED_CACHE]（A）：查询失败且缓存超过有效窗口（或从未收到）→「活性胰岛素本次不可用」；
 * - [INFUSION_ACTIVITY]（B）：近 5 分钟有输注 / 正在输注（含延展）→
 *   「最近输注过大剂量，或有正在输注的大剂量，活性胰岛素可能不准确」。
 *
 * 两者都意味着**本次未扣除**；界面只陈述事实，不给结论、不劝退。
 */
enum class WizardIobUnavailableReason { EXPIRED_CACHE, INFUSION_ACTIVITY }

/** 泵侧计算参数。`carbRatioGramsPerUnit` 已从配置 / 报文的「每单位毫值」折算成 g/U。 */
data class WizardPumpParams(
    val carbRatioGramsPerUnit: Double,
    val isfMgdlPerUnit: Int,
    val targetBgMgdl: Int,
    val maxBolusMilliunits: Int,
    val carbEntryEnabled: Boolean,
    /** 快照收到的本地时间，用于过期判定与界面标注。 */
    val atMillis: Long,
    val origin: WizardDataOrigin = WizardDataOrigin.PUMP,
)

/**
 * 一次泵读数（尚未做时效判定）。只承载泵参数；IOB 由 `ControlIQIOBResponse` 独立入参。
 */
data class WizardPumpReading(
    val params: WizardPumpParams,
    val atMillis: Long,
    val origin: WizardDataOrigin = WizardDataOrigin.PUMP,
)

/**
 * 活性胰岛素读数（上游原始值，本机不加工）。
 *
 * 只接受 `ControlIQIOBResponse` 的值。**超过 `iobStaleAfterMs` 的读数不会构造本对象**
 * （[WizardSnapshots.build] 返回 `null`），界面显示「本次不可用」，不参与运算。
 */
data class WizardIob(
    val milliunits: Int,
    val atMillis: Long,
    val source: WizardIobSource,
)

/** 整份向导不可用的硬性原因；泵参数过期属于**分项**状态，不在此列。 */
enum class WizardUnavailableReason {
    DISABLED,
    NO_PUMP_PARAMS,
    PUMP_PARAMS_INVALID,
}

data class WizardSnapshot(
    val glucose: WizardGlucose,
    /** 泵参数（可能因缓存过期而不可用于计算，仍保留供回显）。 */
    val params: WizardPumpParams?,
    /** 泵参数超过短时效（缓存）：可回显，但**不得参与计算**。 */
    val paramsStale: Boolean,
    /** IOB 独立时效：不随泵参数过期而作废。 */
    val iob: WizardIob?,
    /** IOB 本次不可用的原因（A / B）；[iob] 可用时为 null。 */
    val iobUnavailable: WizardIobUnavailableReason? = null,
    val unavailable: WizardUnavailableReason?,
    val takenAtMillis: Long,
) {
    /** 能否进入计算：硬性原因之外，还要求泵参数在短时效内。 */
    val ready: Boolean get() = unavailable == null && params != null && !paramsStale
}

object WizardSnapshots {

    /** 报文里的 `carbRatio` 是「每单位多少克」的千倍值（1U:6g → 6000）。 */
    fun readingFromPump(
        carbRatioPerMille: Long,
        isfMgdlPerUnit: Int,
        targetBgMgdl: Int,
        maxBolusMilliunits: Int,
        carbEntryEnabled: Boolean,
        atMillis: Long,
    ): WizardPumpReading = WizardPumpReading(
        params = WizardPumpParams(
            carbRatioGramsPerUnit = carbRatioPerMille / 1000.0,
            isfMgdlPerUnit = isfMgdlPerUnit,
            targetBgMgdl = targetBgMgdl,
            maxBolusMilliunits = maxBolusMilliunits,
            carbEntryEnabled = carbEntryEnabled,
            atMillis = atMillis,
        ),
        atMillis = atMillis,
    )

    /**
     * 组装一次快照。所有判断只依赖入参，便于单测；UI 只负责从 DataStore 取值。
     *
     * @param raw 未做过期判断的泵参数读数；`null` 表示这次取数没有拿到参数报文。
     * @param iobMilliunits 最近一条 `ControlIQIOBResponse` 的 IOB；`null` 表示从未收到。
     * @param iobAtMillis 该 IOB 报文的本地到达时刻；无法判定时效时传 `null`。
     * @param queryAtMillis 最近一次**主动查询**的发起时刻（本地时钟）。读数到达时间不早于它，
     *   才算「本次查询」；传 0 表示本次会话尚未查询，全部按缓存处理。
     * @param lastInfusionAtMillis 最近一次输注记录的时刻（本机发送的，或泵历史里新增的）；未知传 null。
     * @param bolusDelivering 泵真相层显示大剂量正在输注（`45` 的 REQUESTING / DELIVERING）。
     * @param extendedBolusActive 泵真相层显示**延展**正在输注（`47` / `-73` 的进行中）。
     */
    fun build(
        cgmMgdl: Int?,
        cgmAtMillis: Long?,
        raw: WizardPumpReading?,
        nowMillis: Long,
        config: WizardConfig,
        /** 传感器型号标识；未知 / 未识别传 null，取保守交集。 */
        sensorKey: String? = null,
        queryAtMillis: Long = 0L,
        iobMilliunits: Int? = null,
        iobAtMillis: Long? = null,
        lastInfusionAtMillis: Long? = null,
        bolusDelivering: Boolean = false,
        extendedBolusActive: Boolean = false,
    ): WizardSnapshot {
        val glucose = glucoseOf(cgmMgdl, cgmAtMillis, nowMillis, config, sensorKey)
        val (iob, iobUnavailable) = iobOf(
            iobMilliunits = iobMilliunits,
            iobAtMillis = iobAtMillis,
            nowMillis = nowMillis,
            config = config,
            queryAtMillis = queryAtMillis,
            lastInfusionAtMillis = lastInfusionAtMillis,
            bolusDelivering = bolusDelivering,
            extendedBolusActive = extendedBolusActive,
        )
        if (!config.enabled) {
            return WizardSnapshot(
                glucose, null, false, iob, iobUnavailable, WizardUnavailableReason.DISABLED, nowMillis,
            )
        }
        if (raw == null) {
            return WizardSnapshot(
                glucose, null, false, iob, iobUnavailable, WizardUnavailableReason.NO_PUMP_PARAMS, nowMillis,
            )
        }
        val params = raw.params
        if (params.carbRatioGramsPerUnit <= 0.0 ||
            params.isfMgdlPerUnit <= 0 ||
            params.targetBgMgdl <= 0
        ) {
            return WizardSnapshot(
                glucose, null, false, iob, iobUnavailable, WizardUnavailableReason.PUMP_PARAMS_INVALID, nowMillis,
            )
        }

        // 本次查询之后到达的读数：参数按新鲜对待（读数本身刚收到）。
        val fromCurrentQuery = queryAtMillis > 0L && raw.atMillis >= queryAtMillis

        // 泵参数：缓存按短时效；本次查询不受此限。
        val paramsStale = !fromCurrentQuery && nowMillis - raw.atMillis > config.snapshotStaleMillis

        return WizardSnapshot(
            glucose = glucose,
            params = params,
            paramsStale = paramsStale,
            iob = iob,
            iobUnavailable = iobUnavailable,
            unavailable = null,
            takenAtMillis = nowMillis,
        )
    }

    /**
     * IOB 取值（严格按序）：
     * 1. **输注否决**先判：近 `recentInfusionVetoMillis` 有输注记录，或泵真相层显示正在输注
     *    （含延展进行中）→ 本次不可靠（B），不构造 [WizardIob]、不扣减。
     * 2. 到达时刻不早于本次查询 → `本次查询`，直接采信。
     * 3. 否则是缓存：距现在不超过 `iobStaleAfterMs`（默认 3 分钟）才可用。
     * 4. 超过窗口 / 从未收到 / 无到达时刻 → 本次不可用（A），返回 `null`。
     */
    private fun iobOf(
        iobMilliunits: Int?,
        iobAtMillis: Long?,
        nowMillis: Long,
        config: WizardConfig,
        queryAtMillis: Long,
        lastInfusionAtMillis: Long?,
        bolusDelivering: Boolean,
        extendedBolusActive: Boolean,
    ): Pair<WizardIob?, WizardIobUnavailableReason?> {
        val recentInfusion = lastInfusionAtMillis != null &&
            nowMillis - lastInfusionAtMillis <= config.recentInfusionVetoMillis
        if (recentInfusion || bolusDelivering || extendedBolusActive) {
            return null to WizardIobUnavailableReason.INFUSION_ACTIVITY
        }
        if (iobMilliunits == null || iobAtMillis == null) {
            return null to WizardIobUnavailableReason.EXPIRED_CACHE
        }
        val fromCurrentQuery = queryAtMillis > 0L && iobAtMillis >= queryAtMillis
        if (!fromCurrentQuery && nowMillis - iobAtMillis > config.iobStaleAfterMs) {
            return null to WizardIobUnavailableReason.EXPIRED_CACHE
        }
        return WizardIob(
            milliunits = iobMilliunits,
            atMillis = iobAtMillis,
            source = if (fromCurrentQuery) WizardIobSource.CURRENT_QUERY else WizardIobSource.CACHE,
        ) to null
    }

    fun glucoseOf(
        mgdl: Int?,
        atMillis: Long?,
        nowMillis: Long,
        config: WizardConfig,
        sensorKey: String? = null,
    ): WizardGlucose {
        // 0 与负值是「没有读数」，不能当成低血糖；这与「空不等于 0」一致。
        if (mgdl == null || mgdl <= 0 || atMillis == null) return WizardGlucose.Unavailable
        val stale = nowMillis - atMillis > config.cgmStaleMillis
        // 预填范围按传感器型号取；越界视作不可用，不把该传感器不会给的值填进去。
        val range = config.cgmPrefillRangeFor(sensorKey)
        val inPrefillRange = mgdl in range.minMgdl..range.maxMgdl
        if (stale || !inPrefillRange) {
            return WizardGlucose(mgdl = null, atMillis = null, stale = stale)
        }
        return WizardGlucose(mgdl = mgdl, atMillis = atMillis, stale = false)
    }
}
