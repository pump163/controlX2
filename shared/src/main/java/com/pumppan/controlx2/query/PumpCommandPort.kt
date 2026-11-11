package com.pumppan.controlx2.query

import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.pumpx2.pump.messages.Message
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * 查询器唯一出口的发送器实现。由调用方注入（大剂量线注入 `MainActivity.sendPumpCommands`），
 * 协议与传输都不变，只是把「发不发」的决定权收到 [PumpCommandPort] 一处。
 */
fun interface PumpRequestSender {
    /** @return 是否真的下发；断连 / 服务未就绪时返回 false */
    fun send(type: SendType, messages: List<Message>): Boolean
}

/**
 * 查询层收敛 · 查询器出口（第 2 步起点）。
 *
 * 解决的问题：大剂量 V2 曾把「同一 opCode 只允许一个在飞 + 超时重发 + 节流」写在
 * `BolusPollScheduler` 内部，与各页面各自的 `sendPumpCommands` 直发并存，形成两套
 * 互不知情的在飞状态。本对象把这三件事收成**一份**，且以实例形式注入，不做全局单例。
 *
 * - **单飞**：同一 opCode 已有请求在飞且未超时 → 拒绝；
 * - **超时**：在飞超过 `timeoutMs` 视为丢包，放行重发；
 * - **节流**：距上次成功发送不足 `minIntervalMs` → 拒绝。
 *
 * 边界（设计 §4.6.6）：只管「发不发」，不管「发什么」——请求类、字段、签名、
 * 安全闸仍在调用方与 `PumpCommHandler`。
 *
 * 状态全在内存、纯同步、无定时器；异常一律吞掉并记日志，绝不阻断发送主路径。
 */
class PumpCommandPort(
    private val sender: PumpRequestSender,
    /** 超过这个时长没有任何响应，链路判为 SUSPECT。由调用方注入 `states.linkSilentAfterMs`。 */
    linkSilentAfterMs: Long = DEFAULT_LINK_SILENT_MS,
    /**
     * 全局在飞上限（不区分 opCode）。
     *
     * 为什么不是只靠单飞去重：单飞只挡「同一条请求重发」，挡不住一次批量塞 16 条不同请求。
     * 实测写失败会在 10s 内连续 3 次就剪线，而批量下发正是最容易凑齐的姿势。上限 3
     * 是兜底，真正让写入错峰的是 `PumpCommHandler` 的批量 200ms 间隔。
     */
    private val maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
    /**
     * 同一条请求（opCode+cargo）的窗口内去重时长；0 表示关闭。
     *
     * 250ms 的依据：泵回 ACK 只需 20-40ms，单飞挡不住「两路各发一遍」；而页面/批量链
     * 之间的重复都发生在几十毫秒内，250ms 足以覆盖，又不会误伤 1s 级的轮询域。
     */
    private val dedupeWindowMs: Long = DEFAULT_DEDUPE_WINDOW_MS,
) {

    enum class Reject {
        /** 同一 opCode 在飞且未超时 */
        IN_FLIGHT,
        /** 距上次发送不足 minIntervalMs */
        THROTTLED,
        /** 发送器返回 false（断连等） */
        SEND_FAILED,
        /** 刚重连，仍在握手静默窗内（`states.connectedSettleMs`） */
        QUIET,
        /** 全局在飞已达上限（批量过密时的兜底） */
        BUSY,
        /** 同一请求在去重窗口内已经发过（挡住「两路各发一遍」） */
        DUPLICATE,
    }

    /** 静默超时阈值；允许在策略加载后注入，避免端口先于策略初始化。 */
    @Volatile
    private var linkSilentAfterMs: Long = linkSilentAfterMs

    /**
     * 重连后的静默窗截止时刻（`System.currentTimeMillis` 语义）；0 表示不在窗口内。
     *
     * 为什么需要：重连后库要跑 `initializeConnection → sendBaseCommands`，这段本身
     * 就在占用链路；App 此时补查是纯叠加，正是边界抖动期最不该做的事。
     */
    private val quietUntil = AtomicLong(0L)

    /** 链路状态。惰性计算，不开线程、不设定时器。 */
    enum class LinkState {
        /** 最近有响应且在 linkSilentAfterMs 内 */
        OK,
        /** 连续发送失败 或 静默超时，此时应暂停周期域、只放行探活 */
        SUSPECT,
        /** 显式标记断开（连接状态 false / 通信挂起） */
        DOWN,
        /**
         * 边界抖动（range-edge flap）：短时间内反复连断，链路能连上但站不稳。
         * 比 SUSPECT 更严：周期域只留保底，事件补查一律丢弃，重连静默窗拉长。
         */
        EDGE,
    }

    /** 一次查询能拿到的数据状态。安全判定只接受 FRESH。 */
    enum class SnapshotState { FRESH, STALE, UNKNOWN }

    enum class UnknownReason {
        /** 链路断开 */
        DISCONNECTED,
        /** 从未收到过该请求的响应 */
        NEVER_RECEIVED,
        /** 收到过，但已超出 staleMaxMs */
        EXPIRED,
    }

    data class Snapshot(
        val message: Message?,
        val ageMs: Long?,
        val state: SnapshotState,
        val reason: UnknownReason? = null,
    )

    data class Result(val sent: Boolean, val reject: Reject?, val opCode: Int)

    /**
     * 单飞键 → 最近一次成功发送的时刻（在飞 + 超时判定）。
     *
     * 键 = opCode（高 32 位）+ cargo 摘要（低 32 位）。只用 opCode 会把
     * `ActiveAamBitsRequest`（opCode 固定 -110，cargo 分别是 2 与 4）的两个请求
     * 当成同一条，第二条被误判 IN_FLIGHT 而丢数据（2026-11-10 真机发现）。
     */
    private val inFlightSince = ConcurrentHashMap<Long, Long>()

    /** 单飞键 → 最近一次成功发送的时刻，用于窗口内去重（见 [Reject.DUPLICATE]）。 */
    private val lastSentAtKey = ConcurrentHashMap<Long, Long>()

    /** opCode → 最近一次成功发送的时刻（节流判定）。节流按请求类共享，不分 cargo。 */
    private val lastSentAt = ConcurrentHashMap<Int, Long>()

    /** 最近一次收到任何响应的时刻；0 表示还没收到过。 */
    private val lastResponseAt = AtomicLong(0L)

    /** 请求 opCode → 最近一次响应（只用于回答「有没有 / 多旧」，不参与调度）。 */
    private val latestResponse = ConcurrentHashMap<Int, Message>()
    private val latestResponseAt = ConcurrentHashMap<Int, Long>()

    /** 显式标记的断开状态（连接状态 false / 通信挂起事件）。 */
    private val linkDown = AtomicBoolean(false)

    /** 连续发送失败次数；≥2 判 SUSPECT，收到响应清零。 */
    private val consecutiveSendFailures = AtomicInteger(0)

    /**
     * 边界抖动（flap）判定参数。默认值即设计 §14 的实测口径，可由策略文件的 `flap` 段注入。
     *
     * 判定只用**连接回调**的时间戳（`markLinkUp` / `markLinkDown` 的 `now`），
     * 不使用 DiagMonitor（设备侧监控已关，拿不到可信的连接时长）。
     */
    data class FlapConfig(
        val windowMs: Long = 300_000L,
        val disconnectsInWindow: Int = 3,
        val shortLivedMs: Long = 30_000L,
        val shortLivedCount: Int = 2,
        val stableAfterMs: Long = 180_000L,
        val quietAfterReconnectMs: Long = 15_000L,
        val probeIntervalMs: Long = 60_000L,
    )

    @Volatile
    private var flap = FlapConfig()

    private val flapLock = Any()

    /** 窗口内的断开时刻（只保留 `windowMs` 内的）。 */
    private val disconnectAt = ArrayDeque<Long>()

    /** 最近若干次连接的存活时长，用于「连续短命连接」判定。 */
    private val connectedForHistory = ArrayDeque<Long>()

    private var lastConnectedAt = 0L
    private var lastDisconnectAt = 0L
    private var edgeActive = false
    private var flapTriggers = 0

    /** 策略加载后注入 `flap` 段；窗口变化时清掉旧样本，避免用旧口径判新阈值。 */
    fun configureFlap(config: FlapConfig) {
        synchronized(flapLock) {
            flap = config
            disconnectAt.clear()
            connectedForHistory.clear()
            edgeActive = false
        }
    }

    /** 当前是否处于 EDGE（顺带做退出判定：稳定期内无断开且拿到过响应）。 */
    fun isEdgeActive(now: Long = System.currentTimeMillis()): Boolean =
        synchronized(flapLock) { edgeActive(now) }

    /** 抖动诊断快照，供 `/api/query/stats` 的 `coordinator.link` 段。 */
    fun flapInfo(now: Long = System.currentTimeMillis()): Map<String, Any?> = synchronized(flapLock) {
        mapOf(
            "edge" to edgeActive(now),
            "triggers" to flapTriggers,
            "disconnectsInWindow" to disconnectAt.count { now - it <= flap.windowMs },
            "windowMs" to flap.windowMs,
            "stableAfterMs" to flap.stableAfterMs,
            "quietAfterReconnectMs" to flap.quietAfterReconnectMs,
            "probeIntervalMs" to flap.probeIntervalMs,
        )
    }

    /**
     * 记录一次断开，并判定是否进入 EDGE。
     *
     * 三条触发条件（设计 §14.2）：
     * 1. `windowMs` 内断开 ≥ `disconnectsInWindow` 次；
     * 2. 连续 `shortLivedCount` 次连接存活 < `shortLivedMs`；
     * 3. 本次连接存活 < 2×`shortLivedMs`（默认 60s）就又断——同一位置的反复连断。
     */
    private fun recordDisconnect(now: Long, connectedForMs: Long) {
        synchronized(flapLock) {
            lastDisconnectAt = now
            disconnectAt.addLast(now)
            while (disconnectAt.isNotEmpty() && now - disconnectAt.first() > flap.windowMs) {
                disconnectAt.removeFirst()
            }
            connectedForHistory.addLast(connectedForMs)
            while (connectedForHistory.size > maxOf(2, flap.shortLivedCount)) {
                connectedForHistory.removeFirst()
            }

            val dense = disconnectAt.size >= flap.disconnectsInWindow
            val shortStreak = connectedForHistory.size >= flap.shortLivedCount &&
                connectedForHistory.takeLast(flap.shortLivedCount).all { it < flap.shortLivedMs }
            val shortReconnect = connectedForMs in 0 until (flap.shortLivedMs * 2)

            if (dense || shortStreak || shortReconnect) {
                if (!edgeActive) {
                    edgeActive = true
                    flapTriggers++
                    Timber.w(
                        "query port: EDGE 抖动判定命中 dense=%s shortStreak=%s shortReconnect=%s connectedForMs=%d",
                        dense, shortStreak, shortReconnect, connectedForMs,
                    )
                }
            }
        }
    }

    /** EDGE 退出：稳定期内无断开，且这段时间里至少收到过一次响应。 */
    private fun edgeActive(now: Long): Boolean {
        if (!edgeActive) return false
        if (now - lastDisconnectAt < flap.stableAfterMs) return true
        if (lastResponseAt.get() <= lastDisconnectAt) return true
        edgeActive = false
        disconnectAt.clear()
        connectedForHistory.clear()
        Timber.i("query port: EDGE 退出（稳定 %dms 且已有响应）", now - lastDisconnectAt)
        return false
    }

    /** 供诊断/单测：某 opCode 是否在飞（该 opCode 下任一条），以及最近一次的时刻。 */
    fun inFlightSince(opCode: Int): Long? =
        inFlightSince.entries
            .filter { opCodeOfKey(it.key) == opCode }
            .maxOfOrNull { it.value }

    /** 供诊断/单测：某 opCode 最近一次成功发送的时刻。 */
    fun lastSentAt(opCode: Int): Long? = lastSentAt[opCode]

    /**
     * 请求一次查询。
     *
     * @param minIntervalMs 节流窗口；0 表示不做节流（一次性对账用）
     * @param timeoutMs     在飞超时；超过即视为丢包，允许重发
     * @param now           调用方时钟（单测可注入）
     */
    fun request(
        message: Message,
        type: SendType = SendType.STANDARD,
        minIntervalMs: Long = 0L,
        timeoutMs: Long = 10_000L,
        now: Long = System.currentTimeMillis(),
    ): Result {
        val opCode = opCodeOf(message)
        val key = queryKey(message)
        return try {
            // 重连静默窗：握手/基线下发期间不放行任何业务查询。
            if (now < quietUntil.get()) return Result(false, Reject.QUIET, opCode)

            // 全局在飞兜底：先清掉早已超时的登记，再看是否已经塞满。
            pruneStaleInFlight(now)
            if (inFlightSince.size >= maxInFlight) return Result(false, Reject.BUSY, opCode)

            val since = inFlightSince[key]
            if (since != null) {
                if (now - since < timeoutMs) return Result(false, Reject.IN_FLIGHT, opCode)
                // 超时 = 丢包，允许重发；不清 lastSentAt，节流窗口仍独立生效。
                inFlightSince.remove(key)
            }

            val last = lastSentAt[opCode]
            if (minIntervalMs > 0L && last != null && now - last < minIntervalMs) {
                return Result(false, Reject.THROTTLED, opCode)
            }

            // 同一条请求的去重窗口。单飞只能挡「还在飞」的那一段，泵回 ACK 很快（实测 20-40ms），
            // 于是两个页面/两条批量链各发一次时，第二条会顺利通过单飞——2026-11-11 21:36
            // 抓到的现象就是同一请求相隔 25ms 连发两遍。用 key（opCode+cargo）而不是 opCode，
            // 避免 ActiveAamBits 这类同 opCode 不同 cargo 的请求被误杀。
            val lastSameKey = lastSentAtKey[key]
            if (dedupeWindowMs > 0L && lastSameKey != null && now - lastSameKey < dedupeWindowMs) {
                return Result(false, Reject.DUPLICATE, opCode)
            }

            val ok = sender.send(type, listOf(message))
            if (ok) {
                inFlightSince[key] = now
                lastSentAt[opCode] = now
                lastSentAtKey[key] = now
                consecutiveSendFailures.set(0)
            } else {
                consecutiveSendFailures.incrementAndGet()
            }
            Result(ok, if (ok) null else Reject.SEND_FAILED, opCode)
        } catch (t: Throwable) {
            consecutiveSendFailures.incrementAndGet()
            safeLog(t)
            Result(false, Reject.SEND_FAILED, opCode)
        }
    }

    /**
     * 收到响应：对应 opCode 不再算在飞。
     *
     * 响应对象自身的 opCode 未必等于请求 opCode，优先按「请求侧」取，
     * 取不到再退回响应自身的 opCode。
     */
    fun onMessage(message: Message, now: Long = System.currentTimeMillis()) {
        try {
            val requestOpCode = try {
                message.getRequestProps()?.opCode?.toInt()?.and(0xFF)
            } catch (_: Throwable) {
                null
            }
            val key = requestOpCode ?: opCodeOf(message)
            // 响应侧拿不到「请求时的 cargo」，只能按 opCode 清掉该请求类的全部在飞。
            // 这是有意放宽：同 opCode 多个 cargo（如 ActiveAamBits 2/4）同时会被一起清，
            // 只削弱防重复强度，不会丢数据。
            clearInFlight(key)

            // 任何一条响应都说明链路活着；同时记下该请求类的数据年龄。
            // 这里同时解除 markLinkDown：通信挂起后只要泵还有回包，就说明链路已恢复。
            linkDown.set(false)
            lastResponseAt.set(now)
            latestResponse[key] = message
            latestResponseAt[key] = now
            consecutiveSendFailures.set(0)
        } catch (t: Throwable) {
            safeLog(t)
        }
    }

    /** 当前链路状态。惰性计算，不开线程、不设定时器。 */
    fun linkState(now: Long = System.currentTimeMillis()): LinkState {
        if (linkDown.get()) return LinkState.DOWN
        // EDGE 优先于 SUSPECT：抖动期「有响应但站不稳」，判定依据是连接事件而不是静默。
        if (isEdgeActive(now)) return LinkState.EDGE
        if (consecutiveSendFailures.get() >= 2) return LinkState.SUSPECT
        val last = lastResponseAt.get()
        if (last == 0L) return LinkState.SUSPECT
        return if (now - last <= linkSilentAfterMs) LinkState.OK else LinkState.SUSPECT
    }

    /**
     * 连接 false / 通信挂起：调度侧应停表，所有周期查询让路。
     *
     * @param recordFlap 是否计入抖动统计。真实断开（连接回调）计入；
     *   `PUMP_COMMUNICATIONS_SUSPENDED` 这类逻辑挂起不计入——否则一次挂起会被当成
     *   一次断开，把「短命连接」的样本污染成假 EDGE。
     */
    fun markLinkDown(now: Long = System.currentTimeMillis(), recordFlap: Boolean = true) {
        val connectedFor = if (lastConnectedAt > 0L) (now - lastConnectedAt).coerceAtLeast(0L) else Long.MAX_VALUE
        linkDown.set(true)
        quietUntil.set(0L)
        if (recordFlap) recordDisconnect(now, connectedFor)
    }

    /**
     * 连接恢复：清掉断开与失败计数，并按需进入静默窗。
     *
     * EDGE 期间静默窗取 `max(quietMs, flap.quietAfterReconnectMs)`：库在重连后要跑
     * `initializeConnection → sendBaseCommands`，这段不能与 App 的补查抢链路。
     *
     * @param quietMs 静默窗时长，取 `states.connectedSettleMs`；0 表示不设窗（单测用）。
     */
    fun markLinkUp(now: Long = System.currentTimeMillis(), quietMs: Long = 0L) {
        lastConnectedAt = now
        linkDown.set(false)
        consecutiveSendFailures.set(0)
        val effectiveQuiet = if (isEdgeActive(now)) maxOf(quietMs, flap.quietAfterReconnectMs) else quietMs
        quietUntil.set(if (effectiveQuiet > 0) now + effectiveQuiet else 0L)
    }

    /** 策略加载后注入 `states.linkSilentAfterMs`；非正值忽略，保持默认。 */
    fun setLinkSilentAfterMs(ms: Long) {
        if (ms > 0L) linkSilentAfterMs = ms
    }

    /** 是否仍处于重连静默窗内（供诊断/单测）。 */
    fun inQuietWindow(now: Long = System.currentTimeMillis()): Boolean = now < quietUntil.get()

    /** 诊断用：当前生效的静默阈值。 */
    fun linkSilentAfterMsValue(): Long = linkSilentAfterMs

    /** 诊断用：全局在飞上限。 */
    fun maxInFlightValue(): Int = maxInFlight

    /** 清掉「早已不可能再有响应」的在飞登记，避免全局计数被死条目永久占满。 */
    private fun pruneStaleInFlight(now: Long) {
        val it = inFlightSince.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > IN_FLIGHT_TTL_MS) it.remove()
        }
    }

    /**
     * 某请求类当前的数据状态。**安全判定只接受 FRESH**；STALE 与 UNKNOWN 一律按「不确定」处理。
     *
     * 只回答「有没有 / 多旧」，不解析业务字段；值仍由调用方从 `DataStore` 取。
     */
    fun snapshot(
        opCode: Int,
        freshMs: Long,
        staleMaxMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Snapshot {
        if (linkState(now) == LinkState.DOWN) {
            return Snapshot(latestResponse[opCode], null, SnapshotState.UNKNOWN, UnknownReason.DISCONNECTED)
        }
        val message = latestResponse[opCode]
            ?: return Snapshot(null, null, SnapshotState.UNKNOWN, UnknownReason.NEVER_RECEIVED)
        val at = latestResponseAt[opCode]
            ?: return Snapshot(message, null, SnapshotState.UNKNOWN, UnknownReason.NEVER_RECEIVED)
        val age = (now - at).coerceAtLeast(0L)
        return when {
            age <= freshMs -> Snapshot(message, age, SnapshotState.FRESH)
            age <= staleMaxMs -> Snapshot(message, age, SnapshotState.STALE)
            else -> Snapshot(message, age, SnapshotState.UNKNOWN, UnknownReason.EXPIRED)
        }
    }

    /** 清掉某 opCode 下的全部在飞（键含 cargo，所以要按 opCode 扫描）。 */
    fun clearInFlight(opCode: Int) {
        val it = inFlightSince.entries.iterator()
        while (it.hasNext()) {
            if (opCodeOfKey(it.next().key) == opCode) it.remove()
        }
    }

    /** 把某 opCode 的节流起点设为 now（新会话 / 对账结束后压制立刻重发）。 */
    fun markThrottle(opCode: Int, now: Long) {
        lastSentAt[opCode] = now
        clearInFlight(opCode)
    }

    /** 全清（单测 / 会话切换）。 */
    fun reset() {
        inFlightSince.clear()
        lastSentAtKey.clear()
        lastSentAt.clear()
        latestResponse.clear()
        latestResponseAt.clear()
        lastResponseAt.set(0L)
        linkDown.set(false)
        quietUntil.set(0L)
        consecutiveSendFailures.set(0)
    }

    /** 协议 opCode 是 signed Byte，`and 0xFF` 后才能与对照表（44 / 46 / 182 …）一致。 */
    private fun opCodeOf(message: Message): Int = message.opCode().toInt() and 0xFF

    /** 单飞键：opCode 放高 32 位，cargo 摘要放低 32 位。 */
    private fun queryKey(message: Message): Long {
        val op = opCodeOf(message).toLong()
        return (op shl 32) or (cargoDigest(message) and 0xFFFFFFFFL)
    }

    private fun opCodeOfKey(key: Long): Int = (key ushr 32).toInt() and 0xFF

    /**
     * cargo 摘要：同 opCode 下用 cargo 区分不同子请求。
     *
     * 目前只有 `ActiveAamBitsRequest` 需要（opCode 恒为 -110，cargo 是 2 或 4）。
     * cargo 为空或取不到时返回 0，等价于旧的「只看 opCode」，不会改变其他请求的行为。
     */
    private fun cargoDigest(message: Message): Long {
        val cargo = try {
            message.cargo
        } catch (_: Throwable) {
            null
        } ?: return 0L
        if (cargo.isEmpty()) return 0L
        var h = 1125899906842597L
        for (b in cargo) h = h * 31 + b.toLong()
        return h
    }

    private fun safeLog(t: Throwable) {
        try {
            Timber.w(t, "PumpCommandPort failure (ignored)")
        } catch (_: Throwable) {
            // 端口自身不允许再抛
        }
    }

    companion object {
        /** 未注入策略时的兜底静默阈值；与 `pump-query-policy.json` 旧默认同值。 */
        const val DEFAULT_LINK_SILENT_MS = 30_000L

        /** 全局在飞兜底上限。 */
        const val DEFAULT_MAX_IN_FLIGHT = 3

        /** 同一条请求的窗口内去重时长。 */
        const val DEFAULT_DEDUPE_WINDOW_MS = 250L

        /** 在飞登记的过期清理阈值：超过它仍未收到回包，本来也会被单飞超时判为丢包。 */
        private const val IN_FLIGHT_TTL_MS = 10_000L
    }
}
