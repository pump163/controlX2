package com.pumppan.controlx2.bolus.state

enum class SessionPhase {
    DRAFT,
    PERMITTING,
    AWAITING_PHONE,
    DELIVERING,
    EXTENDED_RUNNING,
    CANCELLING,
    DONE,
    ABORTED,
    FAILED,
    UNKNOWN,
}

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

    data class Failed(val error: BolusError) : BolusOutcome
}
