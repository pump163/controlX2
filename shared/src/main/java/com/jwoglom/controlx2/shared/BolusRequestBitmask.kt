package com.jwoglom.controlx2.shared

/**
 * `InitiateBolusRequest` 的请求侧 bolusType 位掩码。
 *
 * 数值必须与 `mobile/src/main/assets/pump-capabilities.json` 中
 * `default.encodings.requestBitmask.values` 保持一致（wear 模块读不到该 asset，故在此固化一份，
 * 手机端仍直接从该 JSON 读取）。
 *
 * 禁止改用 pumpx2 的 `BolusDeliveryHistoryLog.BolusType` 推导掩码：1.9.3 起该枚举语义已重构
 * （`CORRECTION` 2→8，新增 `CARB/NOW/LATER` 等），按旧名字改会让发给泵的字节静默变化。
 */
object BolusRequestBitmask {
    const val FOOD1 = 1
    const val CORRECTION = 2
    const val FOOD2 = 8
}
