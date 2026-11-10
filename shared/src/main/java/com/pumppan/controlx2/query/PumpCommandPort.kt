package com.pumppan.controlx2.query

import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.pumpx2.pump.messages.Message
import java.util.concurrent.ConcurrentHashMap
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
class PumpCommandPort(private val sender: PumpRequestSender) {

    enum class Reject {
        /** 同一 opCode 在飞且未超时 */
        IN_FLIGHT,
        /** 距上次发送不足 minIntervalMs */
        THROTTLED,
        /** 发送器返回 false（断连等） */
        SEND_FAILED,
    }

    data class Result(val sent: Boolean, val reject: Reject?, val opCode: Int)

    /**
     * 单飞键 → 最近一次成功发送的时刻（在飞 + 超时判定）。
     *
     * 键 = opCode（高 32 位）+ cargo 摘要（低 32 位）。只用 opCode 会把
     * `ActiveAamBitsRequest`（opCode 固定 -110，cargo 分别是 2 与 4）的两个请求
     * 当成同一条，第二条被误判 IN_FLIGHT 而丢数据（2026-11-10 真机发现）。
     */
    private val inFlightSince = ConcurrentHashMap<Long, Long>()

    /** opCode → 最近一次成功发送的时刻（节流判定）。节流按请求类共享，不分 cargo。 */
    private val lastSentAt = ConcurrentHashMap<Int, Long>()

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

            val ok = sender.send(type, listOf(message))
            if (ok) {
                inFlightSince[key] = now
                lastSentAt[opCode] = now
            }
            Result(ok, if (ok) null else Reject.SEND_FAILED, opCode)
        } catch (t: Throwable) {
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
    fun onMessage(message: Message) {
        try {
            val requestOpCode = try {
                message.getRequestProps()?.opCode?.toInt()?.and(0xFF)
            } catch (_: Throwable) {
                null
            }
            // 响应侧拿不到「请求时的 cargo」，只能按 opCode 清掉该请求类的全部在飞。
            // 这是有意放宽：同 opCode 多个 cargo（如 ActiveAamBits 2/4）同时会被一起清，
            // 只削弱防重复强度，不会丢数据。
            clearInFlight(requestOpCode ?: opCodeOf(message))
        } catch (t: Throwable) {
            safeLog(t)
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
        lastSentAt.clear()
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
}
