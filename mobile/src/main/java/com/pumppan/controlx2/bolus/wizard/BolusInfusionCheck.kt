package com.pumppan.controlx2.bolus.wizard

/**
 * 大剂量前置检查的三态结果。
 *
 * 关键点：`NONE` 是「本次查询确认泵上没有在跑的输注」，`UNKNOWN` 是「这次没问明白」。
 * 两者绝不能并成一个 `false`——界面在 `UNKNOWN` 时不能显示「无进行中输注」，
 * 否则用户会以为泵上是空的，实际只是没拿到响应。
 */
enum class BolusInfusionCheck {

    /** 本次查询确认：立即大剂量与延展大剂量都没有在跑。 */
    NONE,

    /** 本次查询确认：至少有一个在跑。 */
    DELIVERING,

    /** 无法确认：本次查询没拿全两条状态响应（断连 / 超时 / 响应早于本次查询）。 */
    UNKNOWN,
    ;

    /** 只有 `NONE` 才能让向导往下走。 */
    fun allowsStart(): Boolean = this == NONE

    companion object {
        /**
         * @param freshQuery 两条状态响应都晚于本次查询的发起时刻
         * @param bolusDelivering 泵真相层：立即大剂量在输注
         * @param extendedBolusActive 泵真相层：延展大剂量在输注
         */
        fun evaluate(
            freshQuery: Boolean,
            bolusDelivering: Boolean,
            extendedBolusActive: Boolean,
        ): BolusInfusionCheck = when {
            !freshQuery -> UNKNOWN
            bolusDelivering || extendedBolusActive -> DELIVERING
            else -> NONE
        }
    }
}
