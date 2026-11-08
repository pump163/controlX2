package com.pumppan.controlx2.bolus.state

/** CGM 预填范围：按传感器型号各一份；未知型号用保守默认（40–400）。 */
data class CgmPrefillRange(
    val minMgdl: Int = 40,
    val maxMgdl: Int = 400,
)

/**
 * 手动指血输入范围：属于血糖仪，与传感器无关，所以单独一处。
 * 单位换算后仍然分别按 mg/dL 与 mmol/L 判合法。
 */
data class FingerstickRange(
    val mgdlMin: Int = 20,
    val mgdlMax: Int = 600,
    val mmolMin: Double = 1.1,
    val mmolMax: Double = 33.3,
)

/**
 * 向导（大剂量计算器）的机型配置。**全部数值来自 `assets/pump-capabilities.json`**，
 * 本文件只保留兜底默认值，业务代码不得写死阈值。
 *
 * - `fingerstick`：手动指血输入范围（20–600 mg/dL）。
 * - `cgmPrefillBySensor`：CGM 预填范围按传感器型号分别配置（Dexcom 类 40–400、
 *   有的 40–450、Libre 到 500）；未知 / 未识别型号取 [cgmPrefillDefault]（40–400，
 *   即保守交集——宁可少预填，也不填该传感器不可能给出的值）。
 * - 过期判定：泵参数快照 [snapshotStaleMillis]、实时血糖 [cgmStaleMillis]。
 * - `report*ToPump`：`-98` 是否携带碳水 / 血糖 / IOB。
 *   **默认全部 false**：不落可追溯的医疗记录，开源软件不承担该责任（用户 2026-11-08 口径）；
 *   若将来某机型确有需要，只改配置，不改代码。
 */
data class WizardConfig(
    val enabled: Boolean = true,
    /**
     * 碳水输入上限（克）；超出即三态里的「非法」。
     *
     * **临时护栏，无手册依据**：Tandem 手册里没有碳水录入上限（手册唯一的 `300` 是 ICR 范围
     * 1:1–1:300 g/U）；pumpx2 与上游也都没有该常量。当前值只是待实测的临时护栏，
     * 真值在 `pump-capabilities.json` 的 `wizard.maxCarbsGrams` 里，实测后再调。
     */
    val maxCarbsGrams: Int = 1000,
    /** 手动指血输入范围（与传感器无关）。 */
    val fingerstick: FingerstickRange = FingerstickRange(),
    /** 未知传感器型号时的 CGM 预填范围（保守交集 40–400）。 */
    val cgmPrefillDefault: CgmPrefillRange = CgmPrefillRange(),
    /** 按传感器型号的 CGM 预填范围；键为大写型号标识。 */
    val cgmPrefillBySensor: Map<String, CgmPrefillRange> = emptyMap(),
    /** 低于该值按「低血糖」分支计算（校正量为负，IOB 也参与扣减）。 */
    val lowBgMgdl: Int = 70,
    /** 泵参数（ICR / ISF / 目标 / Max Bolus）超过该时长即视为过期，不可参与计算；**不连带作废 IOB**。 */
    val snapshotStaleMillis: Long = 120_000L,
    /** 实时 CGM 读数超过该时长即视为「读不到」，不预填。 */
    val cgmStaleMillis: Long = 300_000L,
    /**
     * IOB 报文的**缓存兜底窗口**（只认 `ControlIQIOBResponse` 的到达时刻）：
     * 主动查询之后到达的值按「本次查询」对待；**查询失败 / 超时**才退回缓存，
     * 缓存不超过该时长才算有效。超过窗口 / 从未收到 → 「活性胰岛素本次不可用」，不显示、不参与运算。
     *
     * 实测衰减 −0.09～−0.11 U/分钟：3 分钟窗口对应约 **0.3 U** 偏差；
     * 若要求 0.2 U 容差，窗口只能是 **2 分钟**。
     */
    val iobStaleAfterMs: Long = 180_000L,
    /**
     * 输注否决窗口：近该时长内存在新的输注记录（本机发送的，或泵历史里新增的），
     * 或泵真相层显示正在输注 / 延展进行中 → IOB 视为不可靠、**不扣减**。
     *
     * 理由是方向性风险：那段时间的缓存值偏**低**，扣得少 → 建议剂量偏高。
     */
    val recentInfusionVetoMillis: Long = 300_000L,
    /** 进入向导标签时的取数节流，避免切标签反复打扰泵。 */
    val fetchThrottleMillis: Long = 10_000L,
    /** `-98` 是否上报碳水（克）。默认否：不留医疗记录。 */
    val reportCarbsToPump: Boolean = false,
    /** `-98` 是否上报血糖（mg/dL）。默认否：不留医疗记录。 */
    val reportBgToPump: Boolean = false,
    /** `-98` 是否上报 IOB（毫单位）。默认否：不留医疗记录。 */
    val reportIobToPump: Boolean = false,
) {
    /** 未知 / 未识别型号取保守默认，绝不回退到任何「更宽」的全局值。 */
    fun cgmPrefillRangeFor(sensorKey: String?): CgmPrefillRange =
        sensorKey?.trim()?.uppercase()?.let { cgmPrefillBySensor[it] } ?: cgmPrefillDefault

    companion object {
        val Default = WizardConfig()
    }
}
