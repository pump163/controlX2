package com.pumppan.controlx2.bolus.state

data class BolusSession(
    val bolusId: Int? = null,
    val phase: SessionPhase = SessionPhase.DRAFT,
    val lastEvidenceAtMillis: Long = 0L,
    val expectedEndAtMillis: Long? = null,
)

enum class BolusActivity {
    IDLE,
    ACTIVE,
    UNKNOWN,
}

/** 动作表「取消」行的五态（对应设计 §4.3 表）。 */
enum class CancelRowState {
    HIDDEN,
    READY,
    NEEDS_ID,
    READ_ONLY,
    RECONCILING,
}

class BolusSessionHolder(
    private val limits: BolusLimits = BolusLimits.Default,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    var session: BolusSession? = null
        private set

    var reconciling: Boolean = false
        private set

    fun begin(
        phase: SessionPhase,
        bolusId: Int? = null,
        expectedEndAtMillis: Long? = null,
        now: Long = clock(),
    ) {
        session = BolusSession(bolusId, phase, now, expectedEndAtMillis)
    }

    /** 收到任一泵侧证据（44 / 46 / -74 / -94 / -96 / 48 / 历史）时刷新时间戳。 */
    fun onEvidence(
        phase: SessionPhase? = null,
        bolusId: Int? = null,
        expectedEndAtMillis: Long? = null,
        now: Long = clock(),
    ) {
        val current = session
        session = BolusSession(
            bolusId = bolusId ?: current?.bolusId,
            phase = phase ?: current?.phase ?: SessionPhase.UNKNOWN,
            lastEvidenceAtMillis = now,
            expectedEndAtMillis = expectedEndAtMillis ?: current?.expectedEndAtMillis,
        )
    }

    fun markReconciling(value: Boolean) {
        reconciling = value
    }

    fun clear() {
        session = null
        reconciling = false
    }

    fun isExpired(now: Long = clock()): Boolean {
        val end = session?.expectedEndAtMillis ?: return true
        return now > end
    }

    /** 三态活性：供「是否允许发起新的大剂量」等门控使用。 */
    fun isAnyBolusActive(connected: Boolean, now: Long = clock()): BolusActivity {
        if (!connected) return BolusActivity.UNKNOWN
        val current = session ?: return BolusActivity.IDLE
        if (!isActivePhase(current.phase)) return BolusActivity.IDLE
        if (current.expectedEndAtMillis != null && now > current.expectedEndAtMillis) {
            return BolusActivity.IDLE
        }
        if (now - current.lastEvidenceAtMillis > limits.staleActiveWindowMillis) {
            return BolusActivity.UNKNOWN
        }
        return BolusActivity.ACTIVE
    }

    /** 五态表：取消行的显示与可点击状态。 */
    fun cancelRowState(connected: Boolean, now: Long = clock()): CancelRowState {
        if (reconciling) return CancelRowState.RECONCILING
        val current = session ?: return CancelRowState.HIDDEN
        if (!isActivePhase(current.phase)) return CancelRowState.HIDDEN
        if (current.expectedEndAtMillis != null && now > current.expectedEndAtMillis) {
            return CancelRowState.HIDDEN
        }
        if (!connected) return CancelRowState.READ_ONLY
        return if (current.bolusId != null) CancelRowState.READY else CancelRowState.NEEDS_ID
    }

    private fun isActivePhase(phase: SessionPhase): Boolean = when (phase) {
        SessionPhase.DELIVERING,
        SessionPhase.EXTENDED_RUNNING,
        SessionPhase.CANCELLING,
        -> true

        SessionPhase.DRAFT,
        SessionPhase.PERMITTING,
        SessionPhase.AWAITING_PHONE,
        SessionPhase.DONE,
        SessionPhase.ABORTED,
        SessionPhase.FAILED,
        SessionPhase.UNKNOWN,
        -> false
    }
}
