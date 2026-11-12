package com.pumppan.controlx2.query

/**
 * 查询层收敛 · 策略外置的不可变模型（schema v2）。
 *
 * 只描述「多久发一次 / 保底多久 / 超了怎么办」；不表达「发什么、能不能发」。
 *
 * v2 相对 v1 的两处增量：
 * - 每个域可以有 `floorIntervalMs` / `floorPriority`：保底档，到期强制发，不看预算与可见性；
 * - `budget.protectPriority` 白名单：列进来的优先级不进降级，`degradeOrder` 只列可降级的。
 *
 * 平台 / 型号的差异由 `base + 覆盖` 目录层在读取时合并（`QueryPolicyLoader`），
 * 不在这里做运行期叠加。「按工作态换节奏」的想法见
 * `docs/feature/查询器改造-推进计划.md` §7.13，尚未实现。
 */
data class QueryPolicy(
    val schemaVersion: Int,
    val source: PolicySource,
    val hash: String,
    val loadedAtMillis: Long,
    val topics: Map<String, TopicPolicy>,
    val freshness: FreshnessPolicy,
    val states: StatesPolicy,
    val budget: BudgetPolicy,
    val events: Map<String, EventPolicy>,
    val reconnect: ReconnectPolicy,
    val switches: SwitchesPolicy,
    /** 边界抖动（flap / EDGE）判定与处置参数，见设计 §14。 */
    val flap: FlapPolicy = FlapPolicy(),
    val diagnostics: PolicyDiagnostics,
    val applyPendingOn: List<String>,
) {
    /** 实际生效的关键值（不是配置值），供 /api/query/stats 的 effective 段。 */
    fun effectiveValues(): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        topics.forEach { (name, t) ->
            out["$name.intervalMs"] = t.intervalMs
            out["$name.minIntervalMs"] = t.minIntervalMs
            out["$name.priority"] = t.priority
            t.floorIntervalMs?.let { out["$name.floorIntervalMs"] = it }
            t.floorPriority?.let { out["$name.floorPriority"] = it }
        }
        out["freshness.freshMs"] = freshness.freshMs
        out["freshness.staleMaxMs"] = freshness.staleMaxMs
        out["freshness.unavailableAfterMs"] = freshness.unavailableAfterMs
        out["budget.perMinute"] = budget.perMinute
        out["budget.burst"] = budget.burst
        out["budget.protectPriority"] = budget.protectPriority.joinToString(",")
        out["budget.degradeOrder"] = budget.degradeOrder.joinToString(",")
        out["states.connectedSettleMs"] = states.connectedSettleMs
        out["states.linkSilentAfterMs"] = states.linkSilentAfterMs
        // 2026-11-10：这两个开关已真正接上消费者（CgmQueryGate），必须出现在 effective 里，
        // 否则改了配置也看不出「到底哪份在生效」。
        out["states.skipCgmTopicWhenSessionStopped"] = states.skipCgmTopicWhenSessionStopped
        out["states.skipCiqTopicsWhenClosedLoopOff"] = states.skipCiqTopicsWhenClosedLoopOff
        out["flap.windowMs"] = flap.windowMs
        out["flap.disconnectsInWindow"] = flap.disconnectsInWindow
        out["flap.shortLivedMs"] = flap.shortLivedMs
        out["flap.stableAfterMs"] = flap.stableAfterMs
        out["flap.quietAfterReconnectMs"] = flap.quietAfterReconnectMs
        return out
    }
}

enum class PolicySource { ASSET, ASSET_OVERRIDE, FALLBACK }

data class TopicPolicy(
    val intervalMs: Long,
    val minIntervalMs: Long,
    val priority: String,
    val onlyWhenVisible: Boolean,
    val maxWindowMs: Long?,
    /** 保底档：非空时，距上次成功查询超过该时长就强制发，不看预算 / 可见性 / 优先级。 */
    val floorIntervalMs: Long? = null,
    /** 保底档的优先级；为空时沿用 [priority]。 */
    val floorPriority: String? = null,
) {
    /**
     * 实际生效周期：保底是「最长允许的空窗」，所以取 `intervalMs` 与 `floorIntervalMs` 的较小者。
     * 没配保底的域按 [intervalMs] 走，不受预算保护。
     */
    fun effectiveIntervalMs(): Long = floorIntervalMs?.let { minOf(intervalMs, it) } ?: intervalMs

    /** 是否有保底档（决定它能否被预算拒绝）。 */
    fun hasFloor(): Boolean = floorIntervalMs != null
}

data class FreshnessPolicy(
    val freshMs: Long,
    val staleMaxMs: Long,
    val unavailableAfterMs: Long,
)

data class StatesPolicy(
    val connectedSettleMs: Long,
    val linkSilentAfterMs: Long,
    val visibilityDebounceMs: Long,
    val skipCgmTopicWhenSessionStopped: Boolean,
    val skipCiqTopicsWhenClosedLoopOff: Boolean,
    val bolusSessionPollMs: Long,
    val extendedSessionPollMs: Long,
)

data class BudgetPolicy(
    val perMinute: Int,
    val burst: Int,
    val burstWindowMs: Long,
    /** 只列可降级项，按顺序先降前面的；不应包含 protectPriority 里的优先级。 */
    val degradeOrder: List<String>,
    /** 白名单：列进来的优先级不进降级、不被预算拒绝。 */
    val protectPriority: List<String> = listOf("P0_FLOOR", "P0_CONFIRM"),
)

data class EventPolicy(
    val topics: List<String>,
    val cooldownMs: Long,
)

data class ReconnectPolicy(
    val probeTopics: List<String>,
    val probeDelayMs: Long,
    val probeTimeoutMs: Long,
    val batchAfterProbeMs: Long,
    val maxBatchAtOnce: Int,
)

/**
 * 边界抖动（range-edge flap）参数：信号在可达极限附近时「连上十几秒又断」。
 *
 * 判定用连接回调的时间戳，不用 DiagMonitor（设备侧监控已关，拿不到可信时长）。
 * 默认值取 2026-11-11 实测：正常连接中位 101.5s，抖动样本 11s / 35s。
 */
data class FlapPolicy(
    val windowMs: Long = 300_000L,
    val disconnectsInWindow: Int = 3,
    val shortLivedMs: Long = 30_000L,
    val shortLivedCount: Int = 2,
    val stableAfterMs: Long = 180_000L,
    val quietAfterReconnectMs: Long = 15_000L,
    val probeIntervalMs: Long = 60_000L,
)

data class SwitchesPolicy(
    val periodicEnabled: Boolean,
    val pageRefreshEnabled: Boolean,
    val backfillEnabled: Boolean,
    val eventDrivenEnabled: Boolean,
)

data class PolicyDiagnostics(
    val missingKeys: List<String>,
    val clamped: List<String>,
    val typeMismatch: List<String>,
    val unknownKeys: List<String>,
    val ignoredKeys: List<String>,
    val rejected: List<String>,
    val hardcodedPolicyFound: List<String>,
    /** `base + 覆盖` 结构下实际读到的 asset 链，如 ["query-policy/base.json", "query-policy/mobile/slimx2.json"]。 */
    val composedFrom: List<String> = emptyList(),
    /** 本次生效的型号档位（slimx2 / mobi），见 [QueryPolicyLoader.resolveDeviceClass]。 */
    val deviceClass: String = "unknown",
    /** 型号档位从哪来：`pref`（设置页手选）/ `auto-default`（AUTO 时的默认档）。 */
    val deviceClassSource: String = "unknown",
)

