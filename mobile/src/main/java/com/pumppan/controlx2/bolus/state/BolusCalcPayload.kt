package com.pumppan.controlx2.bolus.state

/**
 * 向导算出的中间量，随草稿带到确认 / 发送层。
 *
 * 发送层**默认一个都不上报**（见 [WizardConfig.reportCarbsToPump] 等开关）：
 * 不在泵上留下可追溯的医疗记录。这里保留字段只为把开关留成可改的参数，
 * 不是要求现在就写入 `-98`。
 */
data class BolusCalcPayload(
    val foodMilliunits: Int = 0,
    val correctionMilliunits: Int = 0,
    val carbsGrams: Int = 0,
    val bgMgdl: Int? = null,
    val iobMilliunits: Int = 0,
)
