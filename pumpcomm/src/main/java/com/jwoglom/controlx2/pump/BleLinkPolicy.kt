package com.jwoglom.controlx2.pump

import android.content.Context
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * 蓝牙链路策略（外置配置）。
 *
 * 为什么外置：连接参数复位、写失败分级这几个阈值是**按机型/固件行为**定的，
 * Mobi 真机数据还没拿到，写死在 Kotlin 常量里等于每改一次都要重编重装。
 *
 * 来源优先级：
 * 1. `files/ble-link-policy.json` —— `adb push` 到应用私有目录即可调，不用重编；
 * 2. `assets/ble-link-policy.json` —— 随包默认值；
 * 3. 本类内建 [DEFAULT] —— 前两者都读不到或结构坏掉时兜底，永不阻断启动。
 *
 * 解析口径刻意与 `QueryPolicyLoader` 一致：单字段缺失/类型错 → 用默认值并在
 * `diagnostics` 里记一笔；不整份拒绝。诊断列表会随 `/api/diag/state` 的
 * `blePolicy` 一起回报，避免出现「改了 JSON 但没人知道有没有生效」。
 */
object BleLinkPolicy {

    const val ASSET_NAME = "ble-link-policy.json"

    /** filesDir 下的同名覆盖文件，用于免重编调参。 */
    const val OVERRIDE_NAME = "ble-link-policy.json"

    enum class Driver {
        /** 用 pumpx2 自带实现：每 10s 一次 + 参数被改坏时事件驱动复检 + 泵忙豁免。 */
        LIBRARY,

        /** App 侧自建定时器：周期可配，但拿不到库的「历史下载/装药流程」忙判据。实验用。 */
        APP,
    }

    data class Values(
        val reassertEnabled: Boolean,
        val driver: Driver,
        val intervalMs: Long,
        val skipWhenBusy: Boolean,
        val windowMs: Long,
        val cutThreshold: Int,
        val readCooldownMs: Long,
        /** 自动重连期间，泵端「配对码无效」在窗口内累计到这个次数才升级到 UI。 */
        val pairingInvalidEscalateCount: Int,
        /** 上面那个次数的统计窗口；窗口内成功连上会清零。 */
        val pairingInvalidWindowMs: Long,
        /** 距第一次拒绝至少经过这么久仍未成功，才升级；给链路抖动一次自愈机会。 */
        val pairingInvalidEscalateDelayMs: Long,
        /** 断开超过这个时长，提示「已 2 分钟无法连接」（仅提示，不介入库的 autoConnect）。 */
        val reconnectWatchdogStartMs: Long,
        /** 断开超过这个时长，补一次发现链重挂并提示检查距离（默认 15 分钟，约等于库实测上限 928s）。 */
        val reconnectHintMs: Long,
        /** 断开超过这个时长则停止自动尝试，转为提示用户人工干预（默认 30 分钟）。 */
        val reconnectGiveUpMs: Long,
        val source: String,
        val diagnostics: List<String>,
    )

    private val DEFAULT = Values(
        reassertEnabled = true,
        driver = Driver.LIBRARY,
        intervalMs = 10_000L,
        skipWhenBusy = true,
        windowMs = 10_000L,
        cutThreshold = 3,
        readCooldownMs = 500L,
        pairingInvalidEscalateCount = 2,
        pairingInvalidWindowMs = 600_000L,
        pairingInvalidEscalateDelayMs = 60_000L,
        reconnectWatchdogStartMs = 120_000L,
        reconnectHintMs = 900_000L,
        reconnectGiveUpMs = 1_800_000L,
        source = "default",
        diagnostics = emptyList(),
    )

    @Volatile
    private var cached: Values? = null

    /** 首次调用时加载并缓存；改配置需要重启服务进程才生效（与 QueryPolicyLoader 一致）。 */
    fun current(context: Context): Values = cached ?: load(context).also { cached = it }

    /** 供 `/api/diag/state` 回报「真正生效的值」，字段名与 JSON 保持一致。 */
    fun snapshot(values: Values = cached ?: DEFAULT): Map<String, Any?> = mapOf(
        "source" to values.source,
        "reassertEnabled" to values.reassertEnabled,
        "reassertDriver" to values.driver.name.lowercase(),
        "reassertIntervalMs" to values.intervalMs,
        "skipWhenBusy" to values.skipWhenBusy,
        "writeFailureWindowMs" to values.windowMs,
        "writeFailureCutThreshold" to values.cutThreshold,
        "readCooldownMs" to values.readCooldownMs,
        "pairingInvalidEscalateCount" to values.pairingInvalidEscalateCount,
        "pairingInvalidWindowMs" to values.pairingInvalidWindowMs,
        "pairingInvalidEscalateDelayMs" to values.pairingInvalidEscalateDelayMs,
        "reconnectWatchdogStartMs" to values.reconnectWatchdogStartMs,
        "reconnectHintMs" to values.reconnectHintMs,
        "reconnectGiveUpMs" to values.reconnectGiveUpMs,
        "diagnostics" to values.diagnostics,
    )

    private fun load(context: Context): Values {
        // 外置存储的 files 目录（/sdcard/Android/data/<pkg>/files）：release 包也能
        // `adb push` 写入，是「不重编调参」唯一可靠的通道（内部 filesDir 需要 debuggable）。
        val external = context.getExternalFilesDir(null)?.let { File(it, OVERRIDE_NAME) }
        val externalText = if (external?.isFile == true) {
            runCatching { external.readText() }.getOrNull().also {
                if (it == null) Timber.w("BleLinkPolicy: external override unreadable")
            }
        } else {
            null
        }
        val override = File(context.filesDir, OVERRIDE_NAME)
        val overrideText = if (externalText == null && override.isFile) {
            runCatching { override.readText() }.getOrNull().also {
                if (it == null) Timber.w("BleLinkPolicy: override file unreadable, falling back to asset")
            }
        } else {
            null
        }
        val assetText = if (externalText == null && overrideText == null) {
            runCatching { context.assets.open(ASSET_NAME).use { it.readBytes().decodeToString() } }
                .onFailure { Timber.w(it, "BleLinkPolicy: asset missing") }
                .getOrNull()
        } else {
            null
        }
        val text = externalText ?: overrideText ?: assetText
            ?: return DEFAULT.copy(diagnostics = listOf("load:none"))

        val source = when {
            externalText != null -> "external-override"
            overrideText != null -> "internal-override"
            else -> "asset"
        }
        return runCatching { parse(text, source) }
            .onFailure { Timber.w(it, "BleLinkPolicy: parse failed, using defaults") }
            .getOrElse {
                DEFAULT.copy(source = "default", diagnostics = listOf("parse:${it.javaClass.simpleName}"))
            }
            .also {
                Timber.i(
                    "BleLinkPolicy loaded source=%s reassert=%b driver=%s interval=%dms window=%dms cut=%d cooldown=%dms diag=%d",
                    it.source, it.reassertEnabled, it.driver.name.lowercase(), it.intervalMs,
                    it.windowMs, it.cutThreshold, it.readCooldownMs, it.diagnostics.size,
                )
            }
    }

    private fun parse(text: String, source: String): Values {
        val diag = mutableListOf<String>()
        val root = JSONObject(text)

        val r = root.optJSONObject("reassert")
        if (r == null) diag += "reassert:missing"

        val enabled = r.bool("reassert.enabled", DEFAULT.reassertEnabled, "reassert", diag)
        val driverRaw = r.str("reassert.driver", DEFAULT.driver.name.lowercase(), "reassert", diag).lowercase()
        val driver = when (driverRaw) {
            "app" -> Driver.APP
            "library" -> Driver.LIBRARY
            else -> {
                diag += "reassert.driver:unknown($driverRaw)"
                Driver.LIBRARY
            }
        }
        // 5s 下限：再密就是纯噪声，且每次参数更新都要占用一次链路事务。
        val interval = r.num("reassert.intervalMs", DEFAULT.intervalMs, 5_000L, 120_000L, "reassert", diag)
        val skipWhenBusy = r.bool("reassert.skipWhenBusy", DEFAULT.skipWhenBusy, "reassert", diag)

        val w = root.optJSONObject("writeFailure")
        if (w == null) diag += "writeFailure:missing"
        val window = w.num("writeFailure.windowMs", DEFAULT.windowMs, 1_000L, 120_000L, "writeFailure", diag)
        val threshold = w
            .num("writeFailure.cutThreshold", DEFAULT.cutThreshold.toLong(), 1L, 10L, "writeFailure", diag)
            .toInt()
        val cooldown = w.num("writeFailure.readCooldownMs", DEFAULT.readCooldownMs, 0L, 10_000L, "writeFailure", diag)

        // 配对/认证容错：自动重连时泵端可能因链路抖动/远端未开配对窗口而拒绝一次，
        // 不能当成「用户填错码」立刻打断服务；窗口内累计到阈值才升级。
        val p = root.optJSONObject("pairingRetry")
        if (p == null) diag += "pairingRetry:missing"
        val pairingEscalate = p
            .num("pairingRetry.invalidEscalateCount", DEFAULT.pairingInvalidEscalateCount.toLong(), 1L, 20L, "pairingRetry", diag)
            .toInt()
        val pairingWindow = p
            .num("pairingRetry.invalidWindowMs", DEFAULT.pairingInvalidWindowMs, 60_000L, 3_600_000L, "pairingRetry", diag)
        val pairingDelay = p
            .num("pairingRetry.invalidEscalateDelayMs", DEFAULT.pairingInvalidEscalateDelayMs, 5_000L, 600_000L, "pairingRetry", diag)

        // 重连提示分层：库侧 autoConnect 在泵不广播时会空等数分钟（实测 ≥8 分钟无任何 connect）。
        // 断开到 start 只提示并补一次重挂，之后不再反复重试；hint 提示检查距离，giveUp 停手交给用户。
        val rc = root.optJSONObject("reconnect")
        if (rc == null) diag += "reconnect:missing"
        val watchdogStart = rc
            .num("reconnect.watchdogStartMs", DEFAULT.reconnectWatchdogStartMs, 10_000L, 1_800_000L, "reconnect", diag)
        val hintAt = rc
            .num("reconnect.hintMs", DEFAULT.reconnectHintMs, 60_000L, 3_600_000L, "reconnect", diag)
        val giveUpAt = rc
            .num("reconnect.giveUpMs", DEFAULT.reconnectGiveUpMs, 300_000L, 7_200_000L, "reconnect", diag)

        return Values(
            reassertEnabled = enabled,
            driver = driver,
            intervalMs = interval,
            skipWhenBusy = skipWhenBusy,
            windowMs = window,
            cutThreshold = threshold,
            readCooldownMs = cooldown,
            pairingInvalidEscalateCount = pairingEscalate,
            pairingInvalidWindowMs = pairingWindow,
            pairingInvalidEscalateDelayMs = pairingDelay,
            reconnectWatchdogStartMs = watchdogStart,
            reconnectHintMs = hintAt,
            reconnectGiveUpMs = giveUpAt,
            source = source,
            diagnostics = diag,
        )
    }

    // ---- JSON 读字段：缺省/类型错都留痕，不抛异常 ----

    private fun JSONObject?.str(key: String, def: String, path: String, diag: MutableList<String>): String {
        val leaf = key.substringAfterLast('.')
        val o = this ?: return def
        if (!o.has(leaf)) {
            diag += "$path.$leaf:missing"
            return def
        }
        val any = o.opt(leaf)
        if (any !is String) {
            diag += "$path.$leaf:${any?.javaClass?.simpleName}"
            return def
        }
        return any
    }

    private fun JSONObject?.bool(key: String, def: Boolean, path: String, diag: MutableList<String>): Boolean {
        val leaf = key.substringAfterLast('.')
        val o = this ?: return def
        if (!o.has(leaf)) {
            diag += "$path.$leaf:missing"
            return def
        }
        val any = o.opt(leaf)
        if (any !is Boolean) {
            diag += "$path.$leaf:${any?.javaClass?.simpleName}"
            return def
        }
        return any
    }

    private fun JSONObject?.num(
        key: String,
        def: Long,
        min: Long,
        max: Long,
        path: String,
        diag: MutableList<String>,
    ): Long {
        val leaf = key.substringAfterLast('.')
        val o = this ?: return def
        if (!o.has(leaf)) {
            diag += "$path.$leaf:missing"
            return def
        }
        val any = o.opt(leaf)
        if (any !is Number) {
            diag += "$path.$leaf:${any?.javaClass?.simpleName}"
            return def
        }
        val n = any.toLong()
        if (n < min || n > max) {
            diag += "$path.$leaf:$n->${n.coerceIn(min, max)}"
            return n.coerceIn(min, max)
        }
        return n
    }
}
