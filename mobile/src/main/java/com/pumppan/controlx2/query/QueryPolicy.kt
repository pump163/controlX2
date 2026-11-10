package com.pumppan.controlx2.query

/**
 * 查询层收敛 · §4.5 策略外置的不可变模型。
 *
 * 只描述「多久发一次 / 等多久 / 超了怎么办」；不表达「发什么、能不能发」。
 * 见设计文档 §4.5.7 的界线。
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
        }
        out["freshness.freshMs"] = freshness.freshMs
        out["freshness.staleMaxMs"] = freshness.staleMaxMs
        out["freshness.unavailableAfterMs"] = freshness.unavailableAfterMs
        out["budget.perMinute"] = budget.perMinute
        out["budget.burst"] = budget.burst
        out["states.connectedSettleMs"] = states.connectedSettleMs
        out["states.linkSilentAfterMs"] = states.linkSilentAfterMs
        // 2026-11-10：这两个开关已真正接上消费者（CgmQueryGate），必须出现在 effective 里，
        // 否则改了配置也看不出「到底哪份在生效」。
        out["states.skipCgmTopicWhenSessionStopped"] = states.skipCgmTopicWhenSessionStopped
        out["states.skipCiqTopicsWhenClosedLoopOff"] = states.skipCiqTopicsWhenClosedLoopOff
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
)

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
    val degradeOrder: List<String>,
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
)
