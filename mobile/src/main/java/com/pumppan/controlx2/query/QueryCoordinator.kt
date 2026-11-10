package com.pumppan.controlx2.query

import com.pumppan.controlx2.query.PumpCommandPort.LinkState
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/**
 * 查询协调器（最小版）：只做「到期判定 → 合并成一批 → 按优先级下发」。
 *
 * 职责边界（对应设计文档 §3）：
 * - **协调器**：什么时候到期、合并哪些域、按什么顺序、链路不可用时冻不冻；
 * - **端口**（[PumpCommandPort]）：单飞、节流、超时、链路状态、三态快照；
 * - **调用方**：用途（页面订阅 / 一次性任务）。
 *
 * 本类不持有页面，也不起线程：由外部按固定节拍调 [tick]。
 * 没有登记请求清单的域（页面批）会被跳过，不会「发空包」。
 *
 * [Mode.SHADOW] 只计算「本该发什么」而不下发，用于接管页面之前核对节奏与条数；
 * 页面迁完后切 [Mode.ACTIVE]。
 */
class QueryCoordinator(
    private val policy: QueryPolicy,
    private val port: PumpCommandPort,
    private val mode: Mode = Mode.SHADOW,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * 已经由协调器接管的域。登记了请求清单但页面仍在自己发的域不能算进来，
     * 否则同一批请求会被两边各发一次。页面迁完一个加一个。
     */
    private val activeDomains: Set<String> = DEFAULT_ACTIVE_DOMAINS,
) {

    enum class Mode {
        /** 只算不发：记录本该下发哪些域、多少条。 */
        SHADOW,
        /** 真下发。 */
        ACTIVE,
    }

    enum class SkipReason {
        /** 还没到生效周期 */
        NOT_DUE,
        /** 链路显式断开：冻结计时与下发，恢复后立刻补 */
        LINK_DOWN,
        /** 链路可疑（静默超时 / 连续失败）：只放行带保底的域 */
        LINK_SUSPECT,
        /** 该域没有登记请求清单（页面批尚未迁移） */
        NO_REQUESTS,
        /** 登记了请求清单，但还没从页面手里接管过来；此时由旧路径继续发 */
        NOT_MIGRATED,
    }

    data class Plan(
        val atMillis: Long,
        val domains: List<String>,
        val requestCount: Int,
        val sent: Int,
        val rejected: Int,
        val skipped: Map<String, SkipReason>,
        val mode: Mode,
    )

    /** 域 → 上次「计划发出」的时刻。断连时不推进，所以恢复后立即到期。 */
    private val lastPlannedAt = ConcurrentHashMap<String, Long>()

    private val recent = ArrayDeque<Plan>()

    /** 当前模式名，供统计端点展示。 */
    fun modeName(): String = mode.name

    /** 跑一次调度节拍。相同 `now` 重复调用是幂等的（未到期自然跳过）。 */
    fun tick(now: Long = clock()): Plan {
        val link = port.linkState(now)
        val skipped = LinkedHashMap<String, SkipReason>()
        val due = mutableListOf<String>()

        for ((domain, topic) in policy.topics) {
            if (domain !in activeDomains) {
                skipped[domain] = SkipReason.NOT_MIGRATED
                continue
            }
            if (!QueryDomainRequests.isSupported(domain)) {
                skipped[domain] = SkipReason.NO_REQUESTS
                continue
            }

            val last = lastPlannedAt[domain]
            if (last != null && now - last < topic.effectiveIntervalMs()) {
                skipped[domain] = SkipReason.NOT_DUE
                continue
            }

            when (link) {
                LinkState.DOWN -> {
                    skipped[domain] = SkipReason.LINK_DOWN
                    continue
                }
                // 链路可疑时只放行保底域：它们本来就不受预算与可见性约束。
                LinkState.SUSPECT -> if (!topic.hasFloor()) {
                    skipped[domain] = SkipReason.LINK_SUSPECT
                    continue
                }
                LinkState.OK -> Unit
            }

            due += domain
        }

        val ordered = due.sortedBy { rankOf(policy.topics.getValue(it)) }
        var sent = 0
        var rejected = 0

        if (mode == Mode.ACTIVE) {
            for (domain in ordered) {
                val topic = policy.topics.getValue(domain)
                for (message in QueryDomainRequests.forDomain(domain)) {
                    val result = port.request(
                        message = message,
                        minIntervalMs = topic.minIntervalMs,
                        timeoutMs = REQUEST_TIMEOUT_MS,
                        now = now,
                    )
                    if (result.sent) sent++ else rejected++
                }
            }
        }

        // 只有计划发出（或真发出）才推进计时；断连 / 未到期都不推进。
        if (ordered.isNotEmpty()) {
            ordered.forEach { lastPlannedAt[it] = now }
        }

        val plan = Plan(
            atMillis = now,
            domains = ordered,
            requestCount = ordered.sumOf { QueryDomainRequests.forDomain(it).size },
            sent = sent,
            rejected = rejected,
            skipped = skipped,
            mode = mode,
        )
        remember(plan)

        if (ordered.isNotEmpty()) {
            Timber.d(
                "query plan mode=%s domains=%s requests=%d sent=%d rejected=%d",
                mode.name, ordered.joinToString(","), plan.requestCount, sent, rejected,
            )
        }
        return plan
    }

    /** 最近若干次计划（最新在后），供 /api/query/stats 的 coordinator 段。 */
    @Synchronized
    fun recentPlans(): List<Plan> = recent.toList()

    /** 各域距离下次到期的毫秒数；负数表示已到期。 */
    @Synchronized
    fun nextDueIn(now: Long = clock()): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for ((domain, topic) in policy.topics) {
            if (domain !in activeDomains) continue
            if (!QueryDomainRequests.isSupported(domain)) continue
            val last = lastPlannedAt[domain] ?: 0L
            out[domain] = last + topic.effectiveIntervalMs() - now
        }
        return out
    }

    @Synchronized
    fun reset() {
        lastPlannedAt.clear()
        recent.clear()
    }

    private fun rankOf(topic: TopicPolicy): Int = when (topic.floorPriority ?: topic.priority) {
        "P0_FLOOR" -> 0
        "P0_CONFIRM" -> 1
        "P1_STATE" -> 2
        "P2_DECORATIVE" -> 3
        else -> 4
    }

    @Synchronized
    private fun remember(plan: Plan) {
        recent.addLast(plan)
        while (recent.size > MAX_RECENT_PLANS) recent.removeFirst()
    }

    companion object {
        /** 单条查询的在飞超时；与端口默认一致，大剂量向导另用更短的一次性超时。 */
        const val REQUEST_TIMEOUT_MS = 10_000L

        /**
         * 默认接管清单：只含配置里已带保底、且旧路径可从页面摘掉的两个域。
         * 页面迁一个，加一个。
         */
        val DEFAULT_ACTIVE_DOMAINS = setOf("PUMP_VITALS", "IOB")

        private const val MAX_RECENT_PLANS = 20
    }
}
