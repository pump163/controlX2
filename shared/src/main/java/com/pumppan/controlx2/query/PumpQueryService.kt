package com.pumppan.controlx2.query

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * 查询层收敛 · 第 0 步：只读计数桩。
 *
 * 硬约束（对应设计文档 §4.2）：
 * - 只在内存计数，不落盘、不发请求、不改发送顺序/内容/失败语义；
 * - 不新增线程、不新增定时器（纯同步累加）；
 * - 任何异常就地吞掉并记 Timber，绝不影响主路径；
 * - 日志限流（首次 + 每 500 次一条）。
 *
 * 第 0 步没有「调度」逻辑，本对象也**不是** §4.1 的 PumpQueryService 接口实现；
 * 真正的查询器在 B 步才引入，届时本对象降级为它的 diagnostics 数据源。
 */
object PumpQueryService {

    private val startedAtMillis = System.currentTimeMillis()

    private val requestCounts = ConcurrentHashMap<String, AtomicLong>()
    private val sourceCounts = ConcurrentHashMap<String, AtomicLong>()
    private val replyCounts = ConcurrentHashMap<String, AtomicLong>()
    private val lastRequestAtMillis = ConcurrentHashMap<String, Long>()

    /**
     * 「谁在问」维度：key = "TOPIC|来源标识"，如 "ACTIONS|Actions.onStart"。
     *
     * 与 [requestCounts] 分开计数，避免重复计量：requestCounts 反映真实下发量，
     * 这里只反映页面的意图分布，用于回答「这个 topic 是哪个页面在要」。
     * 之所以需要它：所有真实登记都发生在 PumpCommHandler.command() 这一个出口，
     * source 恒为 "PumpCommHandler.command"，从出口看不出调用方是谁。
     */
    private val pageSourceCounts = ConcurrentHashMap<String, AtomicLong>()

    /** 同一 opCode 在这个窗口内再次出现，视为「同一 tick 重复」；250 ms 覆盖一次 bulk 写入。 */
    private const val DUPLICATE_WINDOW_MS = 250L

    /**
     * 第 1 步观测：opCode 级总量，key = "80|CGMStatusRequest"。
     *
     * 为什么需要：requestCounts 按 topic 聚合，粒度太粗——首页一次批量 16 条命令
     * 只落进 4 个桶，看不出「哪条命令被谁重复发了」。
     */
    private val opCodeCounts = ConcurrentHashMap<String, AtomicLong>()

    /**
     * 第 1 步观测：同一 250 ms 窗口内第 2 次及以后的发送次数（key 同上）。
     *
     * 这个值直接等于「合并同 tick 重复后能省下的写入次数」，比总量更适合判断收益；
     * 与 [opCodeCounts] 对照即可算出重复率，两者都为空则说明登记点没接上。
     */
    private val opCodeDuplicateCounts = ConcurrentHashMap<String, AtomicLong>()

    /** 每个 opCode 最近一次出现所属的时间窗口编号（nowMs / DUPLICATE_WINDOW_MS）。 */
    private val lastOpCodeWindow = ConcurrentHashMap<String, Long>()

    private val disconnectedRequestCount = AtomicLong()
    private val blockedRequestCount = AtomicLong()
    private val unknownReplyCount = AtomicLong()

    private val inFlight = AtomicInteger()
    private val maxInFlight = AtomicInteger()

    /**
     * 登记一次「旧路径」的发送尝试。
     *
     * @param topic      归属 topic（映射失败为 UNKNOWN）
     * @param source     发送来源标识，如 "PumpCommHandler.command" / "CommService.periodic"
     * @param connected  登记时刻是否处于已连接；null 表示该调用点无法判定
     * @param willSend   是否真的会下发（前置条件不满足时为 false，仅计数）
     */
    fun noteLegacyRequest(
        topic: PumpTopic,
        source: String,
        connected: Boolean?,
        willSend: Boolean = true,
    ) {
        try {
            val n = increment(requestCounts, topic.name)
            increment(sourceCounts, source)
            lastRequestAtMillis[topic.name] = System.currentTimeMillis()
            if (connected == false) disconnectedRequestCount.incrementAndGet()
            if (!willSend) blockedRequestCount.incrementAndGet()

            if (n == 1L || n % 500L == 0L) {
                Timber.d(
                    "PumpQueryService.noteLegacyRequest topic=%s source=%s connected=%s willSend=%s count=%d",
                    topic.name, source, connected, willSend, n,
                )
            }
        } catch (t: Throwable) {
            safeLog(t)
        }
    }

    /** 登记一次「旧路径」收到的响应。topic 映射失败时只记 unknownReplyCount。 */
    fun noteLegacyReply(topic: PumpTopic?, opCode: Int) {
        try {
            if (topic == null) {
                unknownReplyCount.incrementAndGet()
            } else {
                increment(replyCounts, topic.name)
            }
        } catch (t: Throwable) {
            safeLog(t)
        }
    }

    /**
     * 登记一次「真实下发」的 opCode 级观测（第 1 步）。
     *
     * 与 [noteLegacyRequest] 互补：后者按 topic 聚合，这里按 opCode + 请求类名计数，
     * 并识别「同一 250 ms 窗口内重复」，用于回答「哪些命令在同一 tick 被发了不止一次」。
     * 只观测，不改发送行为、不改顺序。
     */
    fun noteWireRequest(opCode: Int, simpleName: String) {
        try {
            val key = "$opCode|$simpleName"
            increment(opCodeCounts, key)

            val nowMs = System.nanoTime() / 1_000_000L
            val window = nowMs / DUPLICATE_WINDOW_MS
            val prevWindow = lastOpCodeWindow.put(key, window)
            if (prevWindow == window) {
                // 同一窗口内第 2 次及以后：计入「可消除的冗余」
                increment(opCodeDuplicateCounts, key)
            }
        } catch (t: Throwable) {
            safeLog(t)
        }
    }

    /**
     * 登记一次「页面表达」的查询意图（只计入 pageSourceCounts，不参与真实下发计数）。
     *
     * @param topic  页面想要的 topic
     * @param source 页面级来源，如 "Dashboard.onStart" / "CartridgeActions.onStart"
     */
    fun notePageRequest(topic: PumpTopic, source: String) {
        try {
            increment(pageSourceCounts, "${topic.name}|$source")
        } catch (t: Throwable) {
            safeLog(t)
        }
    }

    /** 在飞计数：发送前后各调一次（enter=true / false）。 */
    fun noteInFlight(enter: Boolean) {
        try {
            if (enter) {
                val now = inFlight.incrementAndGet()
                while (true) {
                    val prev = maxInFlight.get()
                    if (now <= prev || maxInFlight.compareAndSet(prev, now)) break
                }
            } else {
                inFlight.decrementAndGet()
            }
        } catch (t: Throwable) {
            safeLog(t)
        }
    }

    fun snapshot(): QueryStatsSnapshot {
        return try {
            QueryStatsSnapshot(
                startedAtMillis = startedAtMillis,
                requestCounts = requestCounts.readAll(),
                opCodeCounts = opCodeCounts.readAll(),
                opCodeDuplicateCounts = opCodeDuplicateCounts.readAll(),
                pageSourceCounts = pageSourceCounts.readAll(),
                sourceCounts = sourceCounts.readAll(),
                replyCounts = replyCounts.readAll(),
                lastRequestAtMillis = lastRequestAtMillis.toMap(),
                disconnectedRequestCount = disconnectedRequestCount.get(),
                blockedRequestCount = blockedRequestCount.get(),
                unknownReplyCount = unknownReplyCount.get(),
                inFlight = inFlight.get(),
                maxInFlight = maxInFlight.get(),
            )
        } catch (t: Throwable) {
            safeLog(t)
            QueryStatsSnapshot(
                startedAtMillis = startedAtMillis,
                requestCounts = emptyMap(),
                opCodeCounts = emptyMap(),
                opCodeDuplicateCounts = emptyMap(),
                pageSourceCounts = emptyMap(),
                sourceCounts = emptyMap(),
                replyCounts = emptyMap(),
                lastRequestAtMillis = emptyMap(),
                disconnectedRequestCount = 0,
                blockedRequestCount = 0,
                unknownReplyCount = 0,
                inFlight = 0,
                maxInFlight = 0,
            )
        }
    }

    fun reset() {
        try {
            requestCounts.clear()
            sourceCounts.clear()
            replyCounts.clear()
            pageSourceCounts.clear()
            opCodeCounts.clear()
            opCodeDuplicateCounts.clear()
            lastOpCodeWindow.clear()
            lastRequestAtMillis.clear()
            disconnectedRequestCount.set(0)
            blockedRequestCount.set(0)
            unknownReplyCount.set(0)
            maxInFlight.set(inFlight.get())
        } catch (t: Throwable) {
            safeLog(t)
        }
    }

    private fun increment(map: ConcurrentHashMap<String, AtomicLong>, key: String): Long {
        val counter = map.getOrPut(key) { AtomicLong() }
        return counter.incrementAndGet()
    }

    private fun ConcurrentHashMap<String, AtomicLong>.readAll(): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for ((k, v) in this) out[k] = v.get()
        return out
    }

    private fun safeLog(t: Throwable) {
        try {
            Timber.w(t, "PumpQueryService counter failure (ignored)")
        } catch (_: Throwable) {
            // 计数器自身不允许再抛
        }
    }
}

data class QueryStatsSnapshot(
    val startedAtMillis: Long,
    val requestCounts: Map<String, Long>,
    /** 第 1 步观测：key = "opCode|SimpleName"，真实下发总量。 */
    val opCodeCounts: Map<String, Long>,
    /** 第 1 步观测：同一 250 ms 窗口内第 2 次及以后的次数（可合并的冗余）。 */
    val opCodeDuplicateCounts: Map<String, Long>,
    /** key = "TOPIC|页面来源"，反映页面意图分布（不参与真实下发计数）。 */
    val pageSourceCounts: Map<String, Long>,
    val sourceCounts: Map<String, Long>,
    val replyCounts: Map<String, Long>,
    val lastRequestAtMillis: Map<String, Long>,
    val disconnectedRequestCount: Long,
    val blockedRequestCount: Long,
    val unknownReplyCount: Long,
    val inFlight: Int,
    val maxInFlight: Int,
)
