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
    val sourceCounts: Map<String, Long>,
    val replyCounts: Map<String, Long>,
    val lastRequestAtMillis: Map<String, Long>,
    val disconnectedRequestCount: Long,
    val blockedRequestCount: Long,
    val unknownReplyCount: Long,
    val inFlight: Int,
    val maxInFlight: Int,
)
