package com.pumppan.controlx2.bolus.state

enum class BolusActivity {
    IDLE,
    ACTIVE,
    UNKNOWN,
}

/** 可同时存在的两笔输注：主笔（App 最近发起 / 泵上最新那笔）与另一笔（通常为泵上已有的扩展）。 */
enum class BolusTarget {
    MAIN,
    EXTENDED,
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
    /** 可以发：带上目标那笔的 bolusId 与它是哪一笔。 */
    data class Send(
        val bolusId: Int,
        val target: BolusTarget = BolusTarget.MAIN,
    ) : CancelDecision

    data object NotActive : CancelDecision
    /** 禁盲取消：活跃但拿不到 bolusId（等 44 补全）。 */
    data object MissingBolusId : CancelDecision
    data object Disconnected : CancelDecision
    data object Reconciling : CancelDecision
    /** 短时间内已尝试 3 次，节流。 */
    data object Throttled : CancelDecision
}

/**
 * 取消三态：取消中（[BolusLimits.cancelConfirmMillis] 窗口）/ 已停止 / 未停止。
 * 两笔各自独立推进 —— 取消扩展不应影响普通那笔的三态，反之亦然。
 */
enum class CancelStatus {
    /** 该笔没有发过 `-96`。 */
    IDLE,

    /** 已发 `-96`，泵尚未给出停止证据（确认窗口内）。 */
    SENDING,

    /** 泵已给出停止证据（`-95` wasCancelled / `-91` STOPPED）。 */
    CONFIRMED,

    /** 窗口内仍未确认停止 → 提示 Bolus Not Stopped、断开管路。 */
    FAILED,
}

/**
 * 会话快照的唯一可变容器。状态语义全部在 [BolusSessionController]，
 * 这里只负责持有两笔、刷新证据时间戳、取消节流与三态判定。
 *
 * **两槽模型**：泵上可以同时存在「一笔扩展 + 一笔普通」。过去的单槽实现只留一个
 * `bolusId`，后发起的那笔会把先前的扩展 id 挤掉，导致：
 * - 细条只能显示最新一笔，另一笔只剩一句只读提示；
 * - 泵给出的「旧扩展已终止」证据因 id 对不上被丢弃，误报 Bolus Not Stopped。
 * 因此这里固定两个槽：`session`（主笔）与 `extendedSession`（另一笔），各自带 id、
 * 状态、结束证据与取消节流。
 */
class BolusSessionHolder(
    private val limits: BolusLimits = BolusLimits.Default,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    var session: BolusDelivery? = null
        private set

    /**
     * 并行在泵上运行的另一笔（通常是「主笔发起前就已经在输注的扩展」）。
     * 与 [session] 地位相同：有 id、有状态、可独立取消、可独立收到停止证据。
     */
    var extendedSession: BolusDelivery? = null
        private set

    /** 会话快照每次变更后回调；持久化层在这里做「仅状态跃迁」过滤。 */
    var onSessionChanged: ((BolusDelivery?) -> Unit)? = null

    var reconciling: Boolean = false
        private set

    /** `-96` 尝试时间戳（按目标分账，用于 15s 内最多 3 次的节流）。 */
    private val cancelAttempts = mutableMapOf<BolusTarget, MutableList<Long>>()

    /** 最近一次真正发出 `-96` 的时间；0 表示该笔未取消过。 */
    private val lastCancelAtMillis = mutableMapOf<BolusTarget, Long>()

    /** 供状态机做取消超时兜底；0 = 该笔未发过 `-96`。 */
    fun cancelSentAtMillis(target: BolusTarget = BolusTarget.MAIN): Long =
        lastCancelAtMillis[target] ?: 0L

    fun begin(
        state: BolusDeliveryState,
        bolusId: Int? = null,
        hasImmediateLeg: Boolean = true,
        expectedEndAtMillis: Long? = null,
        now: Long = clock(),
    ) {
        // 新的主笔覆盖旧会话时，若旧会话「只剩扩展段在跑」，把它整体移入第二槽，
        // 而不是降级成只读快照 —— 这样它仍能收停止证据、仍能单独取消。
        keepRunningExtended(previous = session, newBolusId = bolusId)
        // 换了另一笔 → 上一笔的取消账本不能跟过来（否则新笔一进来就带着「未停止」）。
        if (session?.bolusId != bolusId) lastCancelAtMillis.remove(BolusTarget.MAIN)
        session = BolusDelivery(
            state = state,
            bolusId = bolusId,
            hasImmediateLeg = hasImmediateLeg,
            startedAtMillis = now,
            expectedEndAtMillis = expectedEndAtMillis,
            lastEvidenceAtMillis = now,
        )
        coerceSingleOwner()
        onSessionChanged?.invoke(session)
    }

    /** 直接替换主笔快照（状态机内部使用）。 */
    fun set(delivery: BolusDelivery?) {
        if (delivery != null) {
            keepRunningExtended(previous = session, newBolusId = delivery.bolusId)
            if (session?.bolusId != delivery.bolusId) lastCancelAtMillis.remove(BolusTarget.MAIN)
        }
        session = delivery
        coerceSingleOwner()
        onSessionChanged?.invoke(session)
    }

    /** 直接替换第二笔快照。 */
    fun setExtended(delivery: BolusDelivery?) {
        if (extendedSession?.bolusId != delivery?.bolusId) lastCancelAtMillis.remove(BolusTarget.EXTENDED)
        extendedSession = delivery
        coerceSingleOwner()
        onSessionChanged?.invoke(session)
    }

    /** 在现有主笔快照上做一次原子更新；没有主笔时不做任何事。 */
    fun update(now: Long = clock(), block: (BolusDelivery) -> BolusDelivery) {
        val current = session ?: return
        session = block(current).copy(lastEvidenceAtMillis = now)
        onSessionChanged?.invoke(session)
    }

    /** 在第二笔快照上做一次原子更新；没有第二笔时不做任何事。 */
    fun updateExtended(now: Long = clock(), block: (BolusDelivery) -> BolusDelivery) {
        val current = extendedSession ?: return
        extendedSession = block(current).copy(lastEvidenceAtMillis = now)
        onSessionChanged?.invoke(session)
    }

    /**
     * 落定第二笔的结局。只改第二槽，不碰主笔的 outcome ——
     * 取消扩展时，普通那笔必须继续正常展示与取消。
     */
    fun finishExtended(
        state: BolusDeliveryState,
        result: BolusOutcome,
        now: Long = clock(),
    ) {
        val current = extendedSession?.takeIf { !it.isFinished } ?: return
        extendedSession = current.copy(
            state = state,
            finishedAtMillis = now,
            endObservedAtMillis = current.endObservedAtMillis ?: now,
            outcome = result,
        )
        onSessionChanged?.invoke(session)
    }

    fun markReconciling(value: Boolean) {
        reconciling = value
    }

    fun clear() {
        session = null
        extendedSession = null
        reconciling = false
        cancelAttempts.clear()
        lastCancelAtMillis.clear()
        onSessionChanged?.invoke(null)
    }

    /** 只清会话，保留对账标记：对账期间的消息不能把「对账中」提前结束。 */
    fun clearSession() {
        session = null
        extendedSession = null
        onSessionChanged?.invoke(null)
    }

    /** 新会话开始：清空上一笔的取消节流记录（两笔一起，新一笔从零起算）。 */
    fun resetCancelTracking() {
        cancelAttempts.clear()
        lastCancelAtMillis.clear()
    }

    /** 三态活性：供「是否允许发起新的大剂量」等门控使用。 */
    fun isAnyBolusActive(connected: Boolean, now: Long = clock()): BolusActivity {
        // 对账未完成时不能误报 Idle（可能正在输注，只是还没拿到证据）
        if (reconciling) return BolusActivity.UNKNOWN
        if (!connected) return BolusActivity.UNKNOWN

        val extended = extendedSession?.takeIf { !it.isFinished }
        if (extended != null) {
            if (extended.state == BolusDeliveryState.UNKNOWN) return BolusActivity.UNKNOWN
            return BolusActivity.ACTIVE
        }

        val current = session ?: return BolusActivity.IDLE
        // UNCONFIRMED = 已发出 -98 但还没拿到开始证据：既不能断言在输注，也不能断言没输注。
        if (current.state == BolusDeliveryState.UNKNOWN ||
            current.state == BolusDeliveryState.UNCONFIRMED
        ) return BolusActivity.UNKNOWN
        if (current.isFinished) return BolusActivity.IDLE

        // 延展段只有 47 归零 / -91 才算结束；预计结束时刻过了先按 Unknown 处理，
        // 不在预计时刻把会话判成 Idle（§4.9 结束判据）。
        val expectedEnd = current.expectedEndAtMillis
        if (expectedEnd != null && now > expectedEnd + limits.staleExtendedGraceMillis) {
            return BolusActivity.UNKNOWN
        }

        if (!current.isLive) return BolusActivity.IDLE
        // 证据过期分相：立即段 30 秒（3× 10 秒轮询）；延展段只看 expectedEnd + 5 分钟兜底，
        // 否则 5 分钟一次的兜底查询本身就会被 30 秒窗口误判成 Unknown。
        if (expectedEnd == null && now - current.lastEvidenceAtMillis > limits.staleImmediateMillis) {
            return BolusActivity.UNKNOWN
        }
        return BolusActivity.ACTIVE
    }

    /** 五态表：主笔取消行的显示与可点击状态。 */
    fun cancelRowState(connected: Boolean, now: Long = clock()): CancelRowState =
        cancelRowStateFor(BolusTarget.MAIN, connected, now)

    /** 五态表：指定那笔的取消行状态。 */
    fun cancelRowStateFor(
        target: BolusTarget,
        connected: Boolean,
        now: Long = clock(),
    ): CancelRowState {
        if (reconciling) return CancelRowState.RECONCILING
        val current = when (target) {
            BolusTarget.MAIN -> session
            BolusTarget.EXTENDED -> extendedSession
        } ?: return CancelRowState.HIDDEN
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
     *
     * @param target 取消哪一笔；默认主笔，细条对第二笔显式传 [BolusTarget.EXTENDED]。
     */
    fun requestCancel(
        connected: Boolean,
        now: Long = clock(),
        target: BolusTarget = BolusTarget.MAIN,
    ): CancelDecision {
        if (!connected) return CancelDecision.Disconnected
        if (reconciling) return CancelDecision.Reconciling
        val current = when (target) {
            BolusTarget.MAIN -> session
            BolusTarget.EXTENDED -> extendedSession
        } ?: return CancelDecision.NotActive
        if (current.isFinished) return CancelDecision.NotActive
        if (current.state !in CANCELABLE_STATES) return CancelDecision.NotActive
        val bolusId = current.bolusId ?: return CancelDecision.MissingBolusId

        val attempts = cancelAttempts.getOrPut(target) { mutableListOf() }
        attempts.removeAll { now - it >= limits.cancelThrottleWindowMillis }
        if (attempts.size >= limits.maxCancelAttempts) return CancelDecision.Throttled
        attempts.add(now)
        lastCancelAtMillis[target] = now

        val cancelling = current.copy(
            state = BolusDeliveryState.CANCELLING,
            lastEvidenceAtMillis = now,
        )
        when (target) {
            BolusTarget.MAIN -> session = cancelling
            BolusTarget.EXTENDED -> extendedSession = cancelling
        }
        onSessionChanged?.invoke(session)
        return CancelDecision.Send(bolusId, target)
    }

    /**
     * 取消三态（供取消页文案）。两笔各自独立：
     * - 已落定结局且不是「取消失败」→ [CancelStatus.CONFIRMED]；
     * - 取消失败（UNKNOWN）或发出 `-96` 后满 [BolusLimits.cancelConfirmMillis] 仍未停 → [CancelStatus.FAILED]；
     * - 否则 [CancelStatus.SENDING]。
     */
    fun cancelStatus(
        now: Long = clock(),
        target: BolusTarget = BolusTarget.MAIN,
    ): CancelStatus {
        val sentAt = lastCancelAtMillis[target] ?: 0L
        if (sentAt <= 0L) return CancelStatus.IDLE
        val current = when (target) {
            BolusTarget.MAIN -> session
            BolusTarget.EXTENDED -> extendedSession
        } ?: return CancelStatus.IDLE
        if (current.isFinished) {
            return if (current.state == BolusDeliveryState.UNKNOWN) CancelStatus.FAILED
            else CancelStatus.CONFIRMED
        }
        if (current.state == BolusDeliveryState.UNKNOWN) return CancelStatus.FAILED
        return if (now - sentAt >= limits.cancelConfirmMillis) {
            CancelStatus.FAILED
        } else {
            CancelStatus.SENDING
        }
    }

    /**
     * 两槽物理隔离（泵最多只有一笔普通 + 一笔扩展）。
     *
     * 同一个 `bolusId` 绝不能同时占两个槽：那会让细条渲染出两行完全相同的文字，
     * 并把上一笔的取消失败账本挂到另一笔上（2026-11-11 真机 01:32 的假「未停止」）。
     * - 主槽空、第二槽有值 → 抬成主槽，单笔只占一行；
     * - 同 id 双占 → 保留未落定的那个。
     */
    private fun coerceSingleOwner() {
        val ext = extendedSession
        if (session == null) {
            if (ext != null) {
                session = ext
                extendedSession = null
            }
            return
        }
        if (ext == null) return
        val main = session ?: return
        val mainId = main.bolusId ?: return
        val extId = ext.bolusId ?: return
        if (mainId != extId) return
        if (main.isFinished && !ext.isFinished) {
            session = ext
        }
        extendedSession = null
    }

    /**
     * 主笔被替换时，把「仍在跑的旧扩展」抬进第二槽。
     * 只有「新 id 且与旧 id 不同」才抬：对账 / 无 id 的同笔恢复不能伪造第二笔。
     */
    private fun keepRunningExtended(previous: BolusDelivery?, newBolusId: Int?) {
        if (previous == null) return
        if (previous.isFinished || !previous.isExtendedRunning) return
        if (newBolusId == null || previous.bolusId == newBolusId) return
        // 旧主笔本身就是一笔独立扩展（纯方波，或双波只剩扩展段）：它不能再挂在主槽，
        // 否则新主笔的证据会把它覆盖掉。
        extendedSession = previous
    }

    private companion object {
        val CANCELABLE_STATES = setOf(
            BolusDeliveryState.ACTIVE,
            BolusDeliveryState.CANCELLING,
            BolusDeliveryState.UNKNOWN,
        )
    }
}
