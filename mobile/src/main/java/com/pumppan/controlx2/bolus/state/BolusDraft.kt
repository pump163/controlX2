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
) {
    val totalMilliunits: Int get() = nowMilliunits + laterMilliunits
}
