package com.pumppan.controlx2.bolus.state

enum class BolusShape {
    NORMAL,
    SQUARE,
    DUAL,
}

enum class BolusSource {
    MANUAL,
    CALC,
    PRESET,
}

/**
 * 剂量一律用千分之一单位的整型（milliunit），不用 Double 参与计算。
 */
data class BolusDraft(
    val nowMilliunits: Int = 0,
    val laterMilliunits: Int = 0,
    val extendedSeconds: Int = 0,
    val shape: BolusShape = BolusShape.NORMAL,
    val source: BolusSource = BolusSource.MANUAL,
    /** 向导算出的中间量；只有 `source = CALC` 时有值。是否写进 `-98` 由机型配置决定。 */
    val calc: BolusCalcPayload? = null,
) {
    val totalMilliunits: Int get() = nowMilliunits + laterMilliunits
}
