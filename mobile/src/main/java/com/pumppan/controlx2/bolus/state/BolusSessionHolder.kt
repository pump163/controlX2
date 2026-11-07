package com.pumppan.controlx2.bolus.state

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
    /** 短时间内已尝试 3 次，节流。 */
    data object Throttled : CancelDecision
}

/** S8a 取消三态：取消中（10 秒窗口）/ 已停止 / 未停止（5 秒最坏兜底）。 */
enum class CancelStatus {
    /** 本次会话没有发过 `-96`。 */
    IDLE,

    /** 已发 `-96`，泵尚未给出停止证据（10 秒窗口内）。 */
    SENDING,

    /** 泵已给出停止证据（`-95` wasCancelled / `-91` STOPPED）。 */
    CONFIRMED,

    /** 5 秒内仍未确认停止 → 提示 Bolus Not Stopped、断开管路。 */
    FAILED,
}

/**
 * 会话快照的唯一可变容器。状态语义全部在 [BolusSessionController]，
 * 这里只负责持有、刷新证据时间戳、取消节流与三态判定。
 */
class BolusSessionHolder(
    private val limits: BolusLimits = BolusLimits.Default,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    var session: BolusDelivery? = null
        private set

    var reconciling: Boolean = false
        private set

    /** `-96` 尝试时间戳（用于 15s 内最多 3 次的节流）。 */
    private val cancelAttempts = mutableListOf<Long>()

    /** 最近一次真正发出 `-96` 的时间；0 表示本次会话未取消过。 */
    private var lastCancelAtMillis = 0L

    fun begin(
        state: BolusDeliveryState,
        bolusId: Int? = null,
        hasImmediateLeg: Boolean = true,
        expectedEndAtMillis: Long? = null,
        now: Long = clock(),
    ) {
        session = BolusDelivery(
            state = state,
            bolusId = bolusId,
            hasImmediateLeg = hasImmediateLeg,
            startedAtMillis = now,
            expectedEndAtMillis = expectedEndAtMillis,
            lastEvidenceAtMillis = now,
        )
    }

    /** 直接替换快照（状态机内部使用）。 */
    fun set(delivery: BolusDelivery?) {
        session = delivery
    }

    /** 在现有快照上做一次原子更新；没有会话时不做任何事。 */
    fun update(now: Long = clock(), block: (BolusDelivery) -> BolusDelivery) {
        val current = session ?: return
        session = block(current).copy(lastEvidenceAtMillis = now)
    }

    fun markReconciling(value: Boolean) {
        reconciling = value
    }

    fun clear() {
        session = null
        reconciling = false
        cancelAttempts.clear()
        lastCancelAtMillis = 0L
    }

    /** 只清会话，保留对账标记：对账期间的消息不能把「对账中」提前结束。 */
    fun clearSession() {
        session = null
    }

    /** 新会话开始：清空上一笔的取消节流记录。 */
    fun resetCancelTracking() {
        cancelAttempts.clear()
        lastCancelAtMillis = 0L
    }

    /** 三态活性：供「是否允许发起新的大剂量」等门控使用。 */
    fun isAnyBolusActive(connected: Boolean, now: Long = clock()): BolusActivity {
        // 对账未完成时不能误报 Idle（可能正在输注，只是还没拿到证据）
        if (reconciling) return BolusActivity.UNKNOWN
        if (!connected) return BolusActivity.UNKNOWN
        val current = session ?: return BolusActivity.IDLE
        if (current.state == BolusDeliveryState.UNKNOWN) return BolusActivity.UNKNOWN
        if (current.isFinished) return BolusActivity.IDLE

        // 延展段只有 47 归零 / -91 才算结束；预计结束时刻过了先按 Unknown 处理，
        // 不在预计时刻把会话判成 Idle（§4.9 结束判据）。
        val expectedEnd = current.expectedEndAtMillis
        if (expectedEnd != null && now > expectedEnd + EXTENDED_END_GRACE_MS) {
            return BolusActivity.UNKNOWN
        }

        if (!current.isLive) return BolusActivity.IDLE
        if (now - current.lastEvidenceAtMillis > limits.staleActiveWindowMillis) {
            return BolusActivity.UNKNOWN
        }
        return BolusActivity.ACTIVE
    }

    /** 五态表：取消行的显示与可点击状态。 */
    fun cancelRowState(connected: Boolean, now: Long = clock()): CancelRowState {
        if (reconciling) return CancelRowState.RECONCILING
        val current = session ?: return CancelRowState.HIDDEN
        if (current.isFinished) return CancelRowState.HIDDEN
        return when (current.state) {
            BolusDeliveryState.IDLE,
            BolusDeliveryState.CANCELLED,
            // 等确认期间与旧 AWAITING_PHONE 一致：不给取消入口（禁盲取消）。
            BolusDeliveryState.CONFIRMING,
            BolusDeliveryState.UNCONFIRMED,
            -> CancelRowState.HIDDEN

            // §4.9.2：60s 落 UNKNOWN 后不给重试入口，但**保留取消入口**；
            // 断连时仍按只读处理，避免盲取消。
            BolusDeliveryState.UNKNOWN ->
                if (!connected) CancelRowState.READ_ONLY
                else if (current.bolusId != null) CancelRowState.READY
                else CancelRowState.NEEDS_ID

            BolusDeliveryState.ACTIVE,
            BolusDeliveryState.CANCELLING,
            -> when {
                !connected -> CancelRowState.READ_ONLY
                current.bolusId != null -> CancelRowState.READY
                else -> CancelRowState.NEEDS_ID
            }
        }
    }

    /**
     * S8a：取消前的判定（**禁盲取消**、断连 fail-fast、15s 内最多 3 次）。
     * 返回 [CancelDecision.Send] 时才允许发 `-96`。
     */
    fun requestCancel(connected: Boolean, now: Long = clock()): CancelDecision {
        if (!connected) return CancelDecision.Disconnected
        if (reconciling) return CancelDecision.Reconciling
        val current = session ?: return CancelDecision.NotActive
        if (current.isFinished) return CancelDecision.NotActive
        if (current.state !in CANCELABLE_STATES) return CancelDecision.NotActive
        val bolusId = current.bolusId ?: return CancelDecision.MissingBolusId
        cancelAttempts.removeAll { now - it >= CANCEL_THROTTLE_WINDOW_MS }
        if (cancelAttempts.size >= MAX_CANCEL_ATTEMPTS) return CancelDecision.Throttled
        cancelAttempts.add(now)
        lastCancelAtMillis = now
        session = current.copy(
            state = BolusDeliveryState.CANCELLING,
            lastEvidenceAtMillis = now,
        )
        return CancelDecision.Send(bolusId)
    }

    /**
     * S8a 取消三态（供取消页文案）：
     * - 已落定结局且不是「取消失败」→ [CancelStatus.CONFIRMED]；
     * - 取消失败（UNKNOWN）或发出 `-96` 后满 [CANCEL_STOPPED_WORST_CASE_MS] 仍未停 → [CancelStatus.FAILED]；
     * - 否则 [CancelStatus.SENDING]（手册 §9.9 的 10 秒确认窗口）。
     */
    fun cancelStatus(now: Long = clock()): CancelStatus {
        if (lastCancelAtMillis <= 0L) return CancelStatus.IDLE
        val current = session ?: return CancelStatus.IDLE
        if (current.isFinished) {
            return if (current.state == BolusDeliveryState.UNKNOWN) CancelStatus.FAILED
            else CancelStatus.CONFIRMED
        }
        if (current.state == BolusDeliveryState.UNKNOWN) return CancelStatus.FAILED
        return if (now - lastCancelAtMillis >= CANCEL_STOPPED_WORST_CASE_MS) {
            CancelStatus.FAILED
        } else {
            CancelStatus.SENDING
        }
    }

    private companion object {
        val CANCELABLE_STATES = setOf(
            BolusDeliveryState.ACTIVE,
            BolusDeliveryState.CANCELLING,
            BolusDeliveryState.UNKNOWN,
        )

        const val MAX_CANCEL_ATTEMPTS = 3
        /** 设计文档 §S8a：15 秒窗口内最多 3 次；与下方 5 秒「停止兜底」不是同一个窗口。 */
        const val CANCEL_THROTTLE_WINDOW_MS = 15_000L
        /** 5 秒内仍拿不到停止证据就按「未停止」提示断开管路。 */
        const val CANCEL_STOPPED_WORST_CASE_MS = 5_000L

        /** 延展段过了预计结束时刻仍无 47/-91 证据时的兜底窗口（§4.1 的 5 分钟）。 */
        const val EXTENDED_END_GRACE_MS = 5 * 60_000L
    }
}
