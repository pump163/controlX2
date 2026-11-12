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
    /**
     * 真正由协调器下发的域（默认等于已接管清单）。
     *
     * 存在的理由：ACTIVE 是一个全局模式，但迁移是逐域进行的。没有这一维，
     * 一旦整体切 ACTIVE，所有「已接管但页面还在自己发」的域会立刻双发。
     * 现在只让窗口域（BOLUS_OUTCOME）真发，其余域仍按 SHADOW 只算不发。
     */
    private val dispatchedDomains: Set<String> = activeDomains,
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
        /** 边界抖动（EDGE）：只放保底，其余域不计时也不下发，避免与库的初始化叠加 */
        EDGE,
        /** 该域没有登记请求清单（页面批尚未迁移） */
        NO_REQUESTS,
        /** 登记了请求清单，但还没从页面手里接管过来；此时由旧路径继续发 */
        NOT_MIGRATED,
        /** 窗口域（如 `BOLUS_OUTCOME`）当前没有打开的窗口：大剂量还没开始，不该问 */
        WINDOW_CLOSED,
        /** 页面域当前不可见：策略标了 `onlyWhenVisible`，页面没订阅就不发 */
        NOT_VISIBLE,
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

    /** 域 → 上次入口 kick 的时刻（只用于合并窗，不参与周期计算）。 */
    private val lastKickAt = ConcurrentHashMap<String, Long>()

    /**
     * 入口 kick 意图：进入页面时链路还没就绪，先记下域与失效时刻，链路一恢复由 [tick] 补发一次。
     *
     * 只记一次、30s 内有效：冷启动「pump-connected 广播 ≠ 命令通道可用」那段空窗里
     * 页面先组合是常态，不记这个意图，进页那一眼就会静默丢掉，只能等 60s 周期。
     */
    private val pendingKick = ConcurrentHashMap<String, Long>()

    /**
     * 窗口域的截止时刻（domain → until）。
     *
     * 语义：`BOLUS_OUTCOME` 的 1s 不是「常开」，而是「大剂量确认期才开」。
     * 没有窗口就一条都不发；窗口到期即停，不靠调用方保证关闭。
     */
    private val windowUntil = ConcurrentHashMap<String, Long>()

    /**
     * 页面当前可见的数据域（页面 onStart 订阅 / onStop 退订）。
     *
     * 只对策略里 `onlyWhenVisible=true` 的域生效：页面不在时按 60s 白发一批
     * 正是这次要消掉的浪费。空集合表示「没有任何页面订阅」，这些域一律不发。
     */
    private val visibleDomains = ConcurrentHashMap.newKeySet<String>()

    /**
     * 域 → 本轮批次尚未发完的起始下标。
     *
     * 真机上 `maxInFlight=3`，而端口又是在同一个 Looper 线程上同步发送、响应回调也要等这一拍
     * 结束才处理，所以一批 7 条一次性发完时后 4 条必然被判 BUSY 而丢掉。这里把尾巴留到下一拍
     * （5s 后）续发：既保住「一批只能有 3 条同时在飞」的链路约束，也不会静默丢查询。
     */
    private val pendingFrom = ConcurrentHashMap<String, Int>()

    private val recent = ArrayDeque<Plan>()

    /** 当前模式名，供统计端点展示。 */
    fun modeName(): String = mode.name

    /**
     * 链路侧快照，供 `/api/query/stats` 的 coordinator 段展示。
     *
     * 存在的意义：静默阈值（90s）与静默窗（8s）都是「改了配置但看不到生效」的高危项，
     * 必须能从端点直接读到实际值，而不是靠日志推断。
     */
    fun linkInfo(now: Long = clock()): Map<String, Any?> = buildMap {
        put("linkState", port.linkState(now).name)
        put("linkSilentAfterMs", port.linkSilentAfterMsValue())
        put("inQuietWindow", port.inQuietWindow(now))
        put("maxInFlight", port.maxInFlightValue())
        // S5：抖动判定必须能从端点看到「现在是不是 EDGE、命中过几次」，
        // 否则改完 flap 配置只能靠日志猜。
        putAll(port.flapInfo(now))
    }

    /**
     * 打开窗口域。`durationMs` 传空时取该域策略里的 `maxWindowMs`（`BOLUS_OUTCOME` = 60s）。
     *
     * 调用方：大剂量会话开始（`BolusEntryCoordinator.onBolusInitiated` / 冷启动恢复）；
     * 结束不强制关闭也安全——到期自动失效，避免「忘记关 → 一直 1s 轮询」。
     */
    fun openWindow(domain: String, durationMs: Long? = null, now: Long = clock()) {
        val span = durationMs
            ?: policy.topics[domain]?.maxWindowMs
            ?: DEFAULT_WINDOW_MS
        if (span <= 0) return
        windowUntil[domain] = now + span
        lastPlannedAt.remove(domain)
        Timber.d("query window opened: domain=%s for %d ms", domain, span)
    }

    /** 提前关闭（会话已落定）。 */
    fun closeWindow(domain: String) {
        pendingFrom.remove(domain)
        if (windowUntil.remove(domain) != null) {
            Timber.d("query window closed: domain=%s", domain)
        }
    }

    /** 窗口是否仍开着；顺带清掉过期条目。 */
    fun isWindowOpen(domain: String, now: Long = clock()): Boolean {
        val until = windowUntil[domain] ?: return false
        if (now >= until) {
            windowUntil.remove(domain)
            return false
        }
        return true
    }

    /** 窗口快照，供 `/api/query/stats` 与调试端点核对。 */
    fun windowInfo(now: Long = clock()): Map<String, Any?> = mapOf(
        "domains" to windowUntil.keys.toList(),
        "remainingMs" to windowUntil.mapValues { (_, until) -> (until - now).coerceAtLeast(0) },
        "dispatched" to dispatchedDomains.toList(),
    )

    /** 是否有窗口开着：节拍据此在 1s（窗口期）与 5s（平时）之间切换。 */
    fun hasOpenWindow(now: Long = clock()): Boolean =
        windowUntil.keys.any { isWindowOpen(it, now) }

    /**
     * 页面订阅 / 退订某个数据域。
     *
     * 只有策略里标了 `onlyWhenVisible` 的域受它约束；保底域（PUMP_VITALS 等）
     * 页面不在也照常刷新——首页状态栏依赖它们。
     */
    fun setVisible(domain: String, visible: Boolean) {
        val changed = if (visible) visibleDomains.add(domain) else visibleDomains.remove(domain)
        // 退订时丢掉未发完的尾巴：页面关掉后再补发等于白发，重新进页面会从 0 开始。
        if (!visible) pendingFrom.remove(domain)
        // 订阅时把「上次计划时刻」拨到当下：页面 onStart 自己已经拉过一批，
        // 不这样做协调器会在下一拍立刻再发一次（只是被 15s 节流挡住，等于白撞两拍）。
        if (visible && changed) lastPlannedAt[domain] = clock()
        if (changed) Timber.d("query visibility: domain=%s visible=%s", domain, visible)
    }

    fun isVisible(domain: String): Boolean = domain in visibleDomains

    /**
     * 一次性「立刻查一遍」：页面进入时用，不等周期。
     *
     * 与 [tick] 的区别只有两点：不看 `nextDueIn`、不看窗口是否开着（入口要的就是「先看一眼」）。
     * 其余一律共用——同一端口（去重 / 在飞 / 节流）、同一超时、同一统计口径，所以既
     * 不会绕过去重，也不会在 `/api/query/stats` 里隐身。
     *
     * 抑制重复：同一域 [coalesceMs] 内只发一次（用户连点标签、进页与切标签叠加都只算一拍）。
     * 链路未就绪：只登记一次补发意图（[pendingKick]，30s 内有效），由下一拍 [tick] 补。
     *
     * @return 实际发出的条数；0 表示被合并窗 / 链路 / 在飞挡住（不影响页面继续渲染）。
     */
    fun requestNow(domain: String, coalesceMs: Long = KICK_COALESCE_MS, now: Long = clock()): Int {
        if (mode != Mode.ACTIVE) return 0
        if (domain !in activeDomains || domain !in dispatchedDomains) return 0
        if (!QueryDomainRequests.isSupported(domain)) return 0
        val topic = policy.topics[domain] ?: return 0

        val lastKick = lastKickAt[domain]
        if (lastKick != null && now - lastKick < coalesceMs) return 0
        lastKickAt[domain] = now

        if (port.linkState(now) == LinkState.DOWN) {
            pendingKick[domain] = now + KICK_PENDING_TTL_MS
            Timber.d("query kick deferred: domain=%s (link down)", domain)
            return 0
        }

        val messages = QueryDomainRequests.forDomain(domain)
        var index = pendingFrom[domain] ?: 0
        var sent = 0
        var rejected = 0
        var busy = false
        while (index < messages.size) {
            val result = port.request(
                message = messages[index],
                minIntervalMs = topic.minIntervalMs,
                timeoutMs = REQUEST_TIMEOUT_MS,
                now = now,
            )
            if (result.sent) {
                sent++
                index++
            } else if (result.reject == PumpCommandPort.Reject.BUSY) {
                busy = true
                break
            } else {
                rejected++
                index++
            }
        }
        if (index < messages.size) pendingFrom[domain] = index else pendingFrom.remove(domain)
        // 只有真发出去才推进周期计时：否则入口这一脚会把下一拍推后一整个周期，制造空窗。
        if (sent > 0) lastPlannedAt[domain] = now
        remember(
            Plan(
                atMillis = now,
                domains = if (sent > 0) listOf(domain) else emptyList(),
                requestCount = messages.size,
                sent = sent,
                rejected = rejected,
                skipped = emptyMap(),
                mode = mode,
            )
        )
        if (sent > 0) {
            Timber.d("query kick domain=%s requests=%d sent=%d rejected=%d busy=%s", domain, messages.size, sent, rejected, busy)
        }
        return sent
    }

    /** 可见域快照，供 `/api/query/stats` 核对「页面订阅是否真的接上」。 */
    fun visibleInfo(): List<String> = visibleDomains.toList()

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

            // 页面域：没订阅就不发。判定放在窗口之前——页面不在时窗口域也不会开着，
            // 但把 NOT_VISIBLE 排在前面能让统计直接看出「是没人看，不是没开窗」。
            if (topic.onlyWhenVisible && domain !in visibleDomains) {
                skipped[domain] = SkipReason.NOT_VISIBLE
                continue
            }

            // 窗口域：没有打开的窗口就一条都不发。大剂量没开始时的 1s 查询纯属白打链路。
            if (topic.maxWindowMs != null && !isWindowOpen(domain, now)) {
                skipped[domain] = SkipReason.WINDOW_CLOSED
                continue
            }

            val last = lastPlannedAt[domain]
            // 还有上一拍没发完的尾巴时，不受周期约束：否则要等一整个 60s 才能把剩下的补上。
            if (last != null && now - last < topic.effectiveIntervalMs() && !pendingFrom.containsKey(domain)) {
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
                // 边界抖动期与可疑期一样只留保底；单独一个理由，便于在统计里区分
                // 「静默超时」与「反复连断」，两者在真机上的处置不同。
                LinkState.EDGE -> if (!topic.hasFloor()) {
                    skipped[domain] = SkipReason.EDGE
                    continue
                }
                LinkState.OK -> Unit
            }

            due += domain
        }

        // 入口 kick 的补发：页面比链路就绪得早时，链路一好先补这一拍。
        // 不受周期 / 窗口 / 可见性约束——入口那一眼本来就是一次性任务，不是订阅。
        if (mode == Mode.ACTIVE && link == LinkState.OK) {
            val it = pendingKick.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (now > entry.value) {
                    it.remove()
                } else if (entry.key in dispatchedDomains) {
                    due += entry.key
                    it.remove()
                }
            }
        }

        val ordered = due.distinct().sortedBy { rankOf(policy.topics.getValue(it)) }
        var sent = 0
        var rejected = 0
        val sentDomains = LinkedHashSet<String>()
        // 本轮只推进计时、不发包的已接管域（尚未切到协调器下发）。
        val plannedOnly = LinkedHashSet<String>()

        if (mode == Mode.ACTIVE) {
            for (domain in ordered) {
                // 已接管但尚未交给协调器下发的域：只推进计时（等价 SHADOW），不发包。
                if (domain !in dispatchedDomains) {
                    plannedOnly += domain
                    continue
                }
                val topic = policy.topics.getValue(domain)
                val messages = QueryDomainRequests.forDomain(domain)
                var index = pendingFrom[domain] ?: 0
                var domainSent = 0
                var hitBusy = false
                while (index < messages.size) {
                    val result = port.request(
                        message = messages[index],
                        minIntervalMs = topic.minIntervalMs,
                        timeoutMs = REQUEST_TIMEOUT_MS,
                        now = now,
                    )
                    if (result.sent) {
                        sent++
                        domainSent++
                        index++
                    } else if (result.reject == PumpCommandPort.Reject.BUSY) {
                        // 在飞名额已满：剩下的留到下一拍续发。泵的回包在本机实测要几百毫秒到
                        // 数秒，短退避等不到名额，反而把 serviceLooper 卡住，所以直接让位。
                        hitBusy = true
                        break
                    } else {
                        // IN_FLIGHT / THROTTLED / DUPLICATE 说明这条要么已在飞、要么刚发过，
                        // 对本轮而言等同于已覆盖，跳过它继续发后面的。
                        rejected++
                        index++
                    }
                }
                if (index < messages.size) pendingFrom[domain] = index else pendingFrom.remove(domain)
                // 只有真发出去才算「这个域本轮已跑」。全被 QUIET / BUSY / IN_FLIGHT 挡住时
                // 不能推进计时，否则重连静默窗会把下一次到期推到一整个周期之后，制造空窗。
                if (domainSent > 0) sentDomains += domain
                if (hitBusy) break
            }
        }

        // SHADOW 只算不发：按计划推进，模拟真实节奏；
        // ACTIVE 按「实际发出去的域」推进，被拒的域下一拍立即重试；
        // 已接管但未下发的域也按计划推进，避免 1s 节拍把它们每拍都重算一遍。
        val advanced = if (mode == Mode.ACTIVE) sentDomains + plannedOnly else ordered.toSet()
        advanced.forEach { lastPlannedAt[it] = now }

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
            if (topic.onlyWhenVisible && domain !in visibleDomains) continue
            // 窗口域没开窗时不存在「下次到期」：它本来就不该发。
            if (topic.maxWindowMs != null && !isWindowOpen(domain, now)) continue
            val last = lastPlannedAt[domain] ?: 0L
            out[domain] = last + topic.effectiveIntervalMs() - now
        }
        return out
    }

    @Synchronized
    fun reset() {
        lastPlannedAt.clear()
        pendingFrom.clear()
        lastKickAt.clear()
        pendingKick.clear()
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

        /** 窗口域兜底时长：策略里没配 `maxWindowMs` 时的单次等待上限。 */
        const val DEFAULT_WINDOW_MS = 60_000L

        /** 入口 kick 的合并窗：同一域在这个窗口内只发一次。 */
        const val KICK_COALESCE_MS = 2_000L

        /** 入口 kick 在链路未就绪时的补发有效期。 */
        const val KICK_PENDING_TTL_MS = 30_000L

        /**
         * 默认接管清单：只含配置里已带保底、且旧路径可从页面摘掉的两个域。
         * 页面迁一个，加一个。
         */
        val DEFAULT_ACTIVE_DOMAINS = setOf("PUMP_VITALS", "IOB")

        private const val MAX_RECENT_PLANS = 20
    }
}
