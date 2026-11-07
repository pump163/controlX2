package com.pumppan.controlx2.bolus.state

enum class BolusError {
    NACK,
    ALARM,
    TIMEOUT,
    DISCONNECTED,
    UNKNOWN,
}

sealed interface BolusOutcome {
    data object Pending : BolusOutcome

    data object Success : BolusOutcome

    data object Cancelled : BolusOutcome

    data class Failed(
        val error: BolusError,
        /** `-95` 的原始状态码（取消失败时可用于诊断）。 */
        val statusId: Int? = null,
        /** `-95` 的原始原因码。 */
        val reasonId: Int? = null,
        /** 失败来自取消（`-96`/`-95`）而非发起；文案按「取消失败」处理。 */
        val fromCancel: Boolean = false,
    ) : BolusOutcome
}
