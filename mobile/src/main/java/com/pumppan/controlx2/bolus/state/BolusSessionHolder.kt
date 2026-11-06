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

/** 取消决策：发送层只看这个结果决定是否发 `-96`。 */
sealed interface CancelDecision {
    /** 可以发：带上会话里的 bolusId。 */
    data class Send(val bolusId: Int) : CancelDecision

    data object NotActive : CancelDecision
    /** 禁盲取消：活跃但拿不到 bolusId（等 44 补全）。 */
    data object MissingBolusId : CancelDecision
    data object Disconnected : CancelDecision
    data object Reconciling : CancelDecision
    /** 15s 内已尝试 3 次，节流。 */
    data object Throttled : CancelDecision
}

class BolusSessionHolder(
    private val limits: BolusLimits = BolusLimits.Default,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    var session: BolusSession? = null
        private set

    var reconciling: Boolean = false
        private set

    /** `-96` 尝试时间戳（用于 15s / 3 次节流）。 */
    private val cancelAttempts = mutableListOf<Long>()

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
        cancelAttempts.clear()
    }

    /** 只清会话，保留对账标记：对账期间的消息不能把「对账中」提前结束。 */
    fun clearSession() {
        session = null
    }

    fun isExpired(now: Long = clock()): Boolean {
        val end = session?.expectedEndAtMillis ?: return true
        return now > end
    }

    /** 三态活性：供「是否允许发起新的大剂量」等门控使用。 */
    fun isAnyBolusActive(connected: Boolean, now: Long = clock()): BolusActivity {
        // 对账未完成时不能误报 Idle（可能正在输注，只是还没拿到证据）
        if (reconciling) return BolusActivity.UNKNOWN
        if (!connected) return BolusActivity.UNKNOWN
        val current = session ?: return BolusActivity.IDLE
        if (current.phase == SessionPhase.UNKNOWN) return BolusActivity.UNKNOWN
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
        if (current.phase == SessionPhase.UNKNOWN) return CancelRowState.READ_ONLY
        if (!isActivePhase(current.phase)) return CancelRowState.HIDDEN
        if (current.expectedEndAtMillis != null && now > current.expectedEndAtMillis) {
            return CancelRowState.HIDDEN
        }
        if (!connected) return CancelRowState.READ_ONLY
        return if (current.bolusId != null) CancelRowState.READY else CancelRowState.NEEDS_ID
    }

    /**
     * S8a：取消前的判定（**禁盲取消**、断连 fail-fast、15s 内最多 3 次）。
     * 返回 [CancelDecision.Send] 时才允许发 `-96`。
     */
    fun requestCancel(connected: Boolean, now: Long = clock()): CancelDecision {
        if (!connected) return CancelDecision.Disconnected
        if (reconciling) return CancelDecision.Reconciling
        val current = session ?: return CancelDecision.NotActive
        if (!isActivePhase(current.phase)) return CancelDecision.NotActive
        if (current.expectedEndAtMillis != null && now > current.expectedEndAtMillis) {
            return CancelDecision.NotActive
        }
        val bolusId = current.bolusId ?: return CancelDecision.MissingBolusId
        cancelAttempts.removeAll { now - it >= CANCEL_THROTTLE_WINDOW_MS }
        if (cancelAttempts.size >= MAX_CANCEL_ATTEMPTS) return CancelDecision.Throttled
        cancelAttempts.add(now)
        onEvidence(phase = SessionPhase.CANCELLING, bolusId = bolusId, now = now)
        return CancelDecision.Send(bolusId)
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

    private companion object {
        const val MAX_CANCEL_ATTEMPTS = 3
        /** 手册写 10 秒窗口；技术参数表按 5 秒做最坏兜底。 */
        const val CANCEL_THROTTLE_WINDOW_MS = 5_000L
    }
}
