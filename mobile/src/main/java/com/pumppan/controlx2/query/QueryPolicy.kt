package com.pumppan.controlx2.query

/**
 * 查询层收敛 · 策略外置的不可变模型（schema v2）。
 *
 * 只描述「多久发一次 / 保底多久 / 超了怎么办」；不表达「发什么、能不能发」。
 *
 * v2 相对 v1 的三处增量：
 * - 每个域可以有 `floorIntervalMs` / `floorPriority`：保底档，到期强制发，不看预算与可见性；
 * - `budget.protectPriority` 白名单：列进来的优先级不进降级，`degradeOrder` 只列可降级的；
 * - `overlays`：按泵型号 / API 版本 / 工作态对 default 做叠加，合并顺序见 [PolicyResolver]。
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
    val overlays: PolicyOverlays = PolicyOverlays.empty(),
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
        out["overlays.byModel"] = overlays.byModel.keys.joinToString(",")
        out["overlays.byState"] = overlays.byState.keys.joinToString(",")
        out["overlays.byApiVersion"] = overlays.byApiVersion.joinToString(",") { ">=" + it.minApi }
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
    /** 最近一次 [/PolicyResolver.resolve] 实际应用了哪些层，如 ["byModel:X2", "byState:delivering"]。 */
    val appliedOverlays: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// 分层叠加：default → byModel → byApiVersion(minApi 命中) → byState
// ---------------------------------------------------------------------------

/** 单域覆盖；只写想改的字段，其余沿用下层。`enabled=false` 表示整域停用。 */
data class TopicPolicyOverride(
    val intervalMs: Long? = null,
    val floorIntervalMs: Long? = null,
    val minIntervalMs: Long? = null,
    val priority: String? = null,
    val onlyWhenVisible: Boolean? = null,
    val maxWindowMs: Long? = null,
    val enabled: Boolean? = null,
) {
    fun applyTo(base: TopicPolicy): TopicPolicy = base.copy(
        intervalMs = intervalMs ?: base.intervalMs,
        floorIntervalMs = floorIntervalMs ?: base.floorIntervalMs,
        minIntervalMs = minIntervalMs ?: base.minIntervalMs,
        priority = priority ?: base.priority,
        onlyWhenVisible = onlyWhenVisible ?: base.onlyWhenVisible,
        maxWindowMs = maxWindowMs ?: base.maxWindowMs,
    )
}

data class ApiVersionOverlay(
    val minApi: Int,
    val topics: Map<String, TopicPolicyOverride>,
)

data class PolicyOverlays(
    val byModel: Map<String, Map<String, TopicPolicyOverride>> = emptyMap(),
    val byApiVersion: List<ApiVersionOverlay> = emptyList(),
    val byState: Map<String, Map<String, TopicPolicyOverride>> = emptyMap(),
) {
    companion object {
        fun empty(): PolicyOverlays = PolicyOverlays()
    }
}

data class ResolvedTopics(
    val topics: Map<String, TopicPolicy>,
    /** 实际生效的叠加层，按应用顺序；空表示只用 default。 */
    val appliedOverlays: List<String>,
)

object PolicyResolver {

    /**
     * 合并顺序：`default → byModel(model) → byApiVersion(minApi ≤ 当前值，按 minApi 升序) → byState(state)`。
     *
     * 后应用的层覆盖前面的层；`enabled=false` 会把该域从结果里移除。
     * 任何一层的 model / state 键不匹配就整层跳过，不会「部分匹配」。
     */
    fun resolve(
        policy: QueryPolicy,
        model: String?,
        apiVersion: Int?,
        state: String?,
    ): ResolvedTopics {
        val applied = mutableListOf<String>()
        val topics = LinkedHashMap(policy.topics)

        fun apply(layer: String, overrides: Map<String, TopicPolicyOverride>) {
            if (overrides.isEmpty()) return
            applied += layer
            for ((name, override) in overrides) {
                val base = topics[name] ?: continue
                if (override.enabled == false) {
                    topics.remove(name)
                } else {
                    topics[name] = override.applyTo(base)
                }
            }
        }

        model?.let { m -> policy.overlays.byModel[m]?.let { apply("byModel:$m", it) } }
        apiVersion?.let { v ->
            policy.overlays.byApiVersion
                .filter { it.minApi <= v }
                .sortedBy { it.minApi }
                .forEach { apply("byApiVersion:>=${it.minApi}", it.topics) }
        }
        state?.let { s -> policy.overlays.byState[s]?.let { apply("byState:$s", it) } }

        return ResolvedTopics(topics = topics, appliedOverlays = applied)
    }
}
