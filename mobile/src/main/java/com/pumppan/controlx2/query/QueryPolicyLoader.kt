package com.pumppan.controlx2.query

import android.content.Context
import com.pumppan.controlx2.PumppanPrefs
import com.pumppan.controlx2.PumppanPumpModel
import java.io.FileNotFoundException
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * 查询层收敛 · §4.5.4 / §4.5.5 的读取链路（第 0 步版）。
 *
 * 第 0 步范围（严格按设计文档迁移第 0 步）：
 * - 只读 `assets/query-policy/` 下的策略文件；
 * - **不读** filesDir override、**不做**热更新、**不接**任何消费者（不改调度）；
 * - 启动一次加载，失败用内建 FALLBACK，永不阻断启动；
 * - 校验口径：单字段错 → 缺省 + 标记；结构性错 → 整份拒绝回退。
 *
 * 2026-11-12：目录改为 `base + 覆盖`。
 * - `query-policy/base.json` 放全平台共用的这套值；
 * - `query-policy/<platform>/<model>.json` 只放差异，按字段深合并到 base 之上；
 * - 本轮 mobile 端固定 `platform=mobile`，型号读 [PumppanPrefs.pumpModel]
 *   （AUTO 先按 slimx2，诊断里用 `deviceClassSource` 标出来）；
 * - `query-policy/wear/` 下的两份只是占位，wear 侧代码本轮不动，也不会读到这里。
 */
object QueryPolicyLoader {

    /** `base + 覆盖` 结构里的公共层。 */
    const val BASE_ASSET = "query-policy/base.json"

    /** 旧单文件路径：仅当新目录缺 base 时兜底读取，避免资源改名把整份策略打回硬编码。 */
    const val LEGACY_ASSET = "pump-query-policy.json"

    /** 手机进程只读 mobile 分支；wear 侧将来接入时由 wear 自己的调度器按 `wear` 读。 */
    private const val PLATFORM = "mobile"

    // 注意：不能在此处直接调用 fallbackPolicy()——object 属性按声明顺序初始化，
    // 此时下面的 DEFAULT_* 还是 null。改为惰性兜底（见 current()）。
    @Volatile
    private var current: QueryPolicy? = null

    /** 在 PumppanApplication.onCreate 调用一次；任何异常都在内部消化。 */
    fun initialize(context: Context) {
        val selected = resolveDeviceClass(context)
        val composed = mutableListOf<String>()
        try {
            val baseAsset = when {
                assetExists(context, BASE_ASSET) -> BASE_ASSET
                assetExists(context, LEGACY_ASSET) -> LEGACY_ASSET
                else -> throw FileNotFoundException("asset missing: $BASE_ASSET")
            }
            composed += baseAsset

            var root = JSONObject(readAsset(context, baseAsset))
            val overlayAsset = overlayAssetName(selected.key)
            if (assetExists(context, overlayAsset)) {
                root = deepMerge(root, JSONObject(readAsset(context, overlayAsset)))
                composed += overlayAsset
            }

            val loaded = parse(root.toString(), loadedAtMillis = System.currentTimeMillis())
            current = loaded.copy(
                diagnostics = loaded.diagnostics.copy(
                    composedFrom = composed,
                    deviceClass = selected.key,
                    deviceClassSource = selected.source,
                ),
            )
            Timber.i(
                "QueryPolicyLoader loaded class=%s(%s) from=%s topics=%d fresh=%dms",
                selected.key, selected.source, composed.joinToString(","),
                loaded.topics.size, loaded.freshness.freshMs,
            )
        } catch (t: Throwable) {
            val fallback = fallbackPolicy(loadedAtMillis = System.currentTimeMillis())
            current = fallback.copy(
                diagnostics = fallback.diagnostics.copy(
                    rejected = fallback.diagnostics.rejected + "load:${t.javaClass.simpleName}",
                    composedFrom = composed,
                    deviceClass = selected.key,
                    deviceClassSource = selected.source,
                ),
            )
            Timber.w(t, "QueryPolicyLoader fell back to built-in policy")
        }
    }

    /**
     * 组合 base 与覆盖层，供 [initialize] 与单测共用。
     *
     * 合并规则：对象逐层深合并（`default` → `topics` → 单个域 → 单个字段），数组与标量整体替换，
     * 覆盖层里的 `note` 不参与合并（保留 base 的说明）。本来只覆盖出现的键，未出现的沿用下层。
     */
    internal fun compose(baseText: String, overlayTexts: List<String>): String {
        var root = JSONObject(baseText)
        for (text in overlayTexts) root = deepMerge(root, JSONObject(text))
        return root.toString()
    }

    /** 覆盖层路径：`query-policy/<platform>/<model>.json`。 */
    internal fun overlayAssetName(modelKey: String): String =
        "query-policy/$PLATFORM/$modelKey.json"

    private fun deepMerge(base: JSONObject, overlay: JSONObject): JSONObject {
        // 先整份深拷贝：合并结果不能反过来改到 asset 解析出来的对象。
        val out = JSONObject(base.toString())
        for (key in overlay.keys()) {
            if (key == "note") continue
            val incoming = overlay.opt(key)
            val existing = out.opt(key)
            out.put(
                key,
                if (incoming is JSONObject && existing is JSONObject) {
                    deepMerge(existing, incoming)
                } else {
                    incoming
                },
            )
        }
        return out
    }

    private fun assetExists(context: Context, name: String): Boolean {
        return try {
            context.assets.open(name).use { true }
        } catch (e: FileNotFoundException) {
            false
        }
    }

    private fun readAsset(context: Context, name: String): String {
        return context.assets.open(name).use { it.readBytes().decodeToString() }
    }

    /**
     * 型号档位：沿用设置页的 [PumppanPumpModel]（AUTO / MOBI / SLIM_X2）。
     *
     * AUTO 时先用 slimx2 档，并把来源标成 `auto-default`——诊断里能看出「实际吃的哪份」，
     * 真实型号识别接入后只改这里，配置文件与其余代码都不用动。
     */
    private fun resolveDeviceClass(context: Context): DeviceClass {
        return when (PumppanPrefs.pumpModel(context)) {
            PumppanPumpModel.MOBI -> DeviceClass("mobi", "pref")
            PumppanPumpModel.SLIM_X2 -> DeviceClass("slimx2", "pref")
            PumppanPumpModel.AUTO -> DeviceClass("slimx2", "auto-default")
        }
    }

    private data class DeviceClass(val key: String, val source: String)

    fun current(): QueryPolicy {
        return current ?: fallbackPolicy(loadedAtMillis = 0L).also { current = it }
    }

    fun snapshotJson(): String {
        return try {
            buildJson(current()).toString()
        } catch (t: Throwable) {
            Timber.w(t, "QueryPolicyLoader.snapshotJson failed")
            "{}"
        }
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /** 供单测直接喂文本；生产路径只经 [initialize]。 */
    internal fun parse(text: String, loadedAtMillis: Long): QueryPolicy {
        val root = JSONObject(text)
        val missing = mutableListOf<String>()
        val clamped = mutableListOf<String>()
        val typeMismatch = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        val ignored = mutableListOf<String>()
        val rejected = mutableListOf<String>()

        val schemaVersion = (root.opt("schemaVersion") as? Number)?.toInt() ?: 1
        val default = root.optJSONObject("default")
            ?: throw PolicyRejectedException(listOf("default:missing"))

        val d = Reader(missing, clamped, typeMismatch, unknown, "default")

        // ---- topics ----
        val topicsObj = default.optJSONObject("topics")
            ?: throw PolicyRejectedException(listOf("default.topics:missing"))
        if (topicsObj.length() == 0) throw PolicyRejectedException(listOf("default.topics:empty"))

        val topics = LinkedHashMap<String, TopicPolicy>()
        val names = topicsObj.keys().asSequence().toList()
        for (name in names) {
            val base = DEFAULT_TOPICS[name]
            val o = topicsObj.optJSONObject(name)
            if (o == null) {
                typeMismatch += "default.topics.$name:notObject"
                if (base != null) topics[name] = base
                continue
            }
            val path = "default.topics.$name"
            checkUnknownKeys(
                o,
                setOf(
                    "intervalMs", "minIntervalMs", "priority", "onlyWhenVisible", "maxWindowMs",
                    "floorIntervalMs", "floorPriority",
                ),
                path, d,
            )

            val interval = d.long(o, "intervalMs", base?.intervalMs ?: 60_000L, 1_000L, 3_600_000L, path)
            val minInterval = d.long(o, "minIntervalMs", base?.minIntervalMs ?: 1_000L, 1_000L, 3_600_000L, path)
            val priority = d.string(o, "priority", base?.priority ?: "P1_STATE", path)
            val onlyWhenVisible = d.bool(o, "onlyWhenVisible", base?.onlyWhenVisible ?: true, path)
            val maxWindow = d.optLong(o, "maxWindowMs", base?.maxWindowMs, 1_000L, 180_000L, path)

            // 保底档（schema v2）：floorIntervalMs 是「最长允许的空窗」，到期强制发。
            val floorInterval = d.optLong(o, "floorIntervalMs", base?.floorIntervalMs, 1_000L, 3_600_000L, path)
            val floorPriority = if (o.has("floorPriority") && !o.isNull("floorPriority")) {
                d.string(o, "floorPriority", base?.floorPriority ?: priority, path)
            } else {
                base?.floorPriority
            }

            if (priority !in PRIORITIES) {
                rejected += "$path.priority:unknown($priority)"
            }
            if (minInterval > interval) {
                clamped += "$path.minIntervalMs>intervalMs"
            }
            if (floorInterval != null && floorInterval > interval) {
                clamped += "$path.floorIntervalMs>intervalMs"
            }
            if (floorPriority != null && floorPriority !in PRIORITIES) {
                rejected += "$path.floorPriority:unknown($floorPriority)"
            }
            if (rejected.isEmpty()) {
                topics[name] = TopicPolicy(
                    intervalMs = interval,
                    minIntervalMs = minOf(minInterval, interval),
                    priority = priority,
                    onlyWhenVisible = onlyWhenVisible,
                    maxWindowMs = maxWindow,
                    floorIntervalMs = floorInterval,
                    floorPriority = floorPriority,
                )
            }
        }

        // ---- freshness ----
        val fObj = default.optJSONObject("freshness") ?: JSONObject()
        checkUnknownKeys(fObj, setOf("freshMs", "staleMaxMs", "unavailableAfterMs"), "default.freshness", d)
        val fresh = d.long(fObj, "freshMs", DEFAULT_FRESHNESS.freshMs, 1_000L, 3_600_000L, "default.freshness")
        val stale = d.long(fObj, "staleMaxMs", DEFAULT_FRESHNESS.staleMaxMs, 1_000L, 24 * 3_600_000L, "default.freshness")
        val unavailable = d.long(fObj, "unavailableAfterMs", DEFAULT_FRESHNESS.unavailableAfterMs, 1_000L, 24 * 3_600_000L, "default.freshness")
        if (!(fresh < stale && stale < unavailable)) {
            rejected += "default.freshness:notMonotonic($fresh,$stale,$unavailable)"
        }

        // ---- states ----
        val sObj = default.optJSONObject("states") ?: JSONObject()
        checkUnknownKeys(
            sObj,
            setOf(
                "connectedSettleMs", "linkSilentAfterMs", "visibilityDebounceMs",
                "skipCgmTopicWhenSessionStopped", "skipCiqTopicsWhenClosedLoopOff",
                "bolusSessionPollMs", "extendedSessionPollMs",
            ),
            "default.states", d,
        )
        val states = StatesPolicy(
            connectedSettleMs = d.long(sObj, "connectedSettleMs", DEFAULT_STATES.connectedSettleMs, 0L, 30_000L, "default.states"),
            linkSilentAfterMs = d.long(sObj, "linkSilentAfterMs", DEFAULT_STATES.linkSilentAfterMs, 10_000L, 120_000L, "default.states"),
            visibilityDebounceMs = d.long(sObj, "visibilityDebounceMs", DEFAULT_STATES.visibilityDebounceMs, 0L, 5_000L, "default.states"),
            skipCgmTopicWhenSessionStopped = d.bool(sObj, "skipCgmTopicWhenSessionStopped", DEFAULT_STATES.skipCgmTopicWhenSessionStopped, "default.states"),
            skipCiqTopicsWhenClosedLoopOff = d.bool(sObj, "skipCiqTopicsWhenClosedLoopOff", DEFAULT_STATES.skipCiqTopicsWhenClosedLoopOff, "default.states"),
            bolusSessionPollMs = d.long(sObj, "bolusSessionPollMs", DEFAULT_STATES.bolusSessionPollMs, 5_000L, 60_000L, "default.states"),
            extendedSessionPollMs = d.long(sObj, "extendedSessionPollMs", DEFAULT_STATES.extendedSessionPollMs, 60_000L, 3_600_000L, "default.states"),
        )

        // ---- budget ----
        val bObj = default.optJSONObject("budget") ?: JSONObject()
        checkUnknownKeys(
            bObj,
            setOf("perMinute", "burst", "burstWindowMs", "degradeOrder", "protectPriority"),
            "default.budget", d,
        )
        val perMinute = d.int(bObj, "perMinute", DEFAULT_BUDGET.perMinute, 1, 60, "default.budget")
        val burst = d.int(bObj, "burst", DEFAULT_BUDGET.burst, 1, 240, "default.budget")
        val burstWindow = d.long(bObj, "burstWindowMs", DEFAULT_BUDGET.burstWindowMs, 10_000L, 300_000L, "default.budget")
        val degradeOrder = d.stringList(bObj, "degradeOrder", DEFAULT_BUDGET.degradeOrder, "default.budget")
        val protectPriority = d.stringList(bObj, "protectPriority", DEFAULT_BUDGET.protectPriority, "default.budget")
        if (perMinute <= 0) rejected += "default.budget.perMinute:<=0"
        val badProtect = protectPriority.filter { it !in PRIORITIES }
        if (badProtect.isNotEmpty()) {
            rejected += "default.budget.protectPriority:unknown(${badProtect.joinToString(",")})"
        }
        // v2：degradeOrder 只列「可降级」项，不要求覆盖全部优先级；protected 的不能出现在里面，
        // 否则配置读起来像「先降 P0」。
        val badDegrade = degradeOrder.filter { it !in PRIORITIES || it in protectPriority }
        if (badDegrade.isNotEmpty()) {
            rejected += "default.budget.degradeOrder:invalid(${badDegrade.joinToString(",")})"
        }

        // ---- events ----
        val eObj = default.optJSONObject("events") ?: JSONObject()
        val events = LinkedHashMap<String, EventPolicy>()
        for (name in eObj.keys().asSequence().toList()) {
            val o = eObj.optJSONObject(name)
            if (o == null) {
                typeMismatch += "default.events.$name:notObject"
                continue
            }
            val path = "default.events.$name"
            checkUnknownKeys(o, setOf("topics", "cooldownMs"), path, d)
            val eventTopics = d.stringList(o, "topics", DEFAULT_EVENTS[name]?.topics ?: emptyList(), path)
            if (eventTopics.isEmpty()) {
                ignored += "$path.topics:empty"
                continue
            }
            val bad = eventTopics.filter { it !in topics.keys }
            if (bad.isNotEmpty()) {
                ignored += "$path.topics:unknown(${bad.joinToString(",")})"
            }
            events[name] = EventPolicy(
                topics = eventTopics.filter { it in topics.keys },
                cooldownMs = d.long(o, "cooldownMs", DEFAULT_EVENTS[name]?.cooldownMs ?: 5_000L, 1_000L, 600_000L, path),
            )
        }

        // ---- reconnect ----
        val rObj = default.optJSONObject("reconnect") ?: JSONObject()
        checkUnknownKeys(rObj, setOf("probeTopics", "probeDelayMs", "probeTimeoutMs", "batchAfterProbeMs", "maxBatchAtOnce"), "default.reconnect", d)
        val probeTopics = d.stringList(rObj, "probeTopics", DEFAULT_RECONNECT.probeTopics, "default.reconnect")
        if (probeTopics.isEmpty()) rejected += "default.reconnect.probeTopics:empty"
        val reconnect = ReconnectPolicy(
            probeTopics = probeTopics.filter { it in topics.keys },
            probeDelayMs = d.long(rObj, "probeDelayMs", DEFAULT_RECONNECT.probeDelayMs, 0L, 15_000L, "default.reconnect"),
            probeTimeoutMs = d.long(rObj, "probeTimeoutMs", DEFAULT_RECONNECT.probeTimeoutMs, 5_000L, 60_000L, "default.reconnect"),
            batchAfterProbeMs = d.long(rObj, "batchAfterProbeMs", DEFAULT_RECONNECT.batchAfterProbeMs, 0L, 30_000L, "default.reconnect"),
            maxBatchAtOnce = d.int(rObj, "maxBatchAtOnce", DEFAULT_RECONNECT.maxBatchAtOnce, 1, 5, "default.reconnect"),
        )

        // ---- switches ----
        val wObj = default.optJSONObject("switches") ?: JSONObject()
        checkUnknownKeys(wObj, setOf("periodicEnabled", "pageRefreshEnabled", "backfillEnabled", "eventDrivenEnabled"), "default.switches", d)
        val switches = SwitchesPolicy(
            periodicEnabled = d.bool(wObj, "periodicEnabled", DEFAULT_SWITCHES.periodicEnabled, "default.switches"),
            pageRefreshEnabled = d.bool(wObj, "pageRefreshEnabled", DEFAULT_SWITCHES.pageRefreshEnabled, "default.switches"),
            backfillEnabled = d.bool(wObj, "backfillEnabled", DEFAULT_SWITCHES.backfillEnabled, "default.switches"),
            eventDrivenEnabled = d.bool(wObj, "eventDrivenEnabled", DEFAULT_SWITCHES.eventDrivenEnabled, "default.switches"),
        )

        // ---- flap（S5：边界抖动判定，见设计 §14）----
        val flapObj = default.optJSONObject("flap") ?: JSONObject()
        checkUnknownKeys(
            flapObj,
            setOf(
                "windowMs", "disconnectsInWindow", "shortLivedMs", "shortLivedCount",
                "stableAfterMs", "quietAfterReconnectMs", "probeIntervalMs",
            ),
            "default.flap", d,
        )
        val flap = FlapPolicy(
            windowMs = d.long(flapObj, "windowMs", DEFAULT_FLAP.windowMs, 60_000L, 1_800_000L, "default.flap"),
            disconnectsInWindow = d.int(flapObj, "disconnectsInWindow", DEFAULT_FLAP.disconnectsInWindow, 2, 10, "default.flap"),
            shortLivedMs = d.long(flapObj, "shortLivedMs", DEFAULT_FLAP.shortLivedMs, 5_000L, 120_000L, "default.flap"),
            shortLivedCount = d.int(flapObj, "shortLivedCount", DEFAULT_FLAP.shortLivedCount, 2, 5, "default.flap"),
            stableAfterMs = d.long(flapObj, "stableAfterMs", DEFAULT_FLAP.stableAfterMs, 30_000L, 900_000L, "default.flap"),
            quietAfterReconnectMs = d.long(
                flapObj, "quietAfterReconnectMs", DEFAULT_FLAP.quietAfterReconnectMs,
                0L, 60_000L, "default.flap",
            ),
            probeIntervalMs = d.long(
                flapObj, "probeIntervalMs", DEFAULT_FLAP.probeIntervalMs,
                15_000L, 300_000L, "default.flap",
            ),
        )

        if (rejected.isNotEmpty()) throw PolicyRejectedException(rejected)

        // 顶层未知键。`platform` / `model` 是目录覆盖层带来的元信息（读取时已用于选文件，
        // 这里只做声明，不参与调度），不能算 unknown。
        for (k in root.keys()) {
            if (k !in setOf("schemaVersion", "note", "default", "models", "platform", "model")) {
                unknown += "root.$k:unknown"
            }
        }
        for (k in default.keys()) {
            if (k !in setOf("topics", "freshness", "states", "budget", "events", "reconnect", "switches", "flap")) {
                unknown += "default.$k:unknown"
            }
        }

        return QueryPolicy(
            schemaVersion = schemaVersion,
            source = PolicySource.ASSET,
            hash = sha256(text),
            loadedAtMillis = loadedAtMillis,
            topics = topics,
            freshness = FreshnessPolicy(fresh, stale, unavailable),
            states = states,
            budget = BudgetPolicy(perMinute, maxOf(burst, perMinute), burstWindow, degradeOrder),
            events = events,
            reconnect = reconnect,
            switches = switches,
            flap = flap,
            diagnostics = PolicyDiagnostics(
                missingKeys = missing,
                clamped = clamped,
                typeMismatch = typeMismatch,
                unknownKeys = unknown,
                ignoredKeys = ignored,
                rejected = emptyList(),
                hardcodedPolicyFound = STATIC_HARDCODED_POLICY,
            ),
            applyPendingOn = emptyList(),
        )
    }

    private class Reader(
        val missing: MutableList<String>,
        val clamped: MutableList<String>,
        val typeMismatch: MutableList<String>,
        val unknown: MutableList<String>,
        val prefix: String,
    ) {
        fun long(o: JSONObject, key: String, def: Long, min: Long, max: Long, path: String): Long {
            val v = readNumber(o, key, def, path) ?: return def
            val n = v.toLong()
            if (n < min || n > max) {
                clamped += "$path.$key:$n->${n.coerceIn(min, max)}"
                return n.coerceIn(min, max)
            }
            return n
        }

        fun int(o: JSONObject, key: String, def: Int, min: Int, max: Int, path: String): Int {
            val v = readNumber(o, key, def.toLong(), path) ?: return def
            val n = v.toInt()
            if (n < min || n > max) {
                clamped += "$path.$key:$n->${n.coerceIn(min, max)}"
                return n.coerceIn(min, max)
            }
            return n
        }

        fun optLong(o: JSONObject, key: String, def: Long?, min: Long, max: Long, path: String): Long? {
            if (!o.has(key) || o.isNull(key)) return def
            val any = o.opt(key)
            if (any !is Number) {
                typeMismatch += "$path.$key:${any?.javaClass?.simpleName}"
                return def
            }
            return long(o, key, def ?: min, min, max, path)
        }

        fun bool(o: JSONObject, key: String, def: Boolean, path: String): Boolean {
            if (!o.has(key)) return def
            val any = o.opt(key)
            if (any !is Boolean) {
                typeMismatch += "$path.$key:${any?.javaClass?.simpleName}"
                return def
            }
            return any
        }

        fun string(o: JSONObject, key: String, def: String, path: String): String {
            if (!o.has(key)) return def
            val any = o.opt(key)
            if (any !is String) {
                typeMismatch += "$path.$key:${any?.javaClass?.simpleName}"
                return def
            }
            return any
        }

        fun stringList(o: JSONObject, key: String, def: List<String>, path: String): List<String> {
            if (!o.has(key)) return def
            val arr = o.optJSONArray(key)
            if (arr == null) {
                typeMismatch += "$path.$key:notArray"
                return def
            }
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isBlank()) {
                    typeMismatch += "$path.$key[$i]:blank"
                } else {
                    out += v
                }
            }
            return out
        }

        private fun readNumber(o: JSONObject, key: String, def: Long, path: String): Number? {
            if (!o.has(key)) {
                missing += "$path.$key"
                return null
            }
            val any = o.opt(key)
            if (any !is Number) {
                typeMismatch += "$path.$key:${any?.javaClass?.simpleName}"
                return null
            }
            return any
        }
    }

    private fun checkUnknownKeys(o: JSONObject, known: Set<String>, path: String, d: Reader) {
        for (k in o.keys()) {
            if (k !in known) d.unknown += "$path.$k:unknown"
        }
    }

    private class PolicyRejectedException(val reasons: List<String>) : Exception(reasons.joinToString("; "))

    private fun sha256(text: String): String {
        return try {
            val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            "sha256:" + bytes.joinToString("") { "%02x".format(it) }.take(16)
        } catch (t: Throwable) {
            "sha256:unknown"
        }
    }

    // ------------------------------------------------------------------
    // 内建缺省（与 assets 同名同值；FALLBACK 与 asset 必须逐项相等）
    // ------------------------------------------------------------------

    private fun topic(
        interval: Long,
        min: Long,
        priority: String,
        visible: Boolean,
        window: Long? = null,
        floor: Long? = null,
        floorPriority: String? = null,
    ) = TopicPolicy(interval, min, priority, visible, window, floor, floorPriority)

    val DEFAULT_TOPICS: Map<String, TopicPolicy> = linkedMapOf(
       "NOTIFICATIONS" to topic(60_000, 15_000, "P1_STATE", true),
        "ACTIONS" to topic(60_000, 15_000, "P1_STATE", true),
        "CARTRIDGE" to topic(60_000, 15_000, "P1_STATE", true),
        "PROFILE" to topic(60_000, 15_000, "P1_STATE", true),
        "CONTROL_IQ" to topic(300_000, 15_000, "P1_STATE", false, null, 300_000, "P0_FLOOR"),
        "SAFETY_LIMITS" to topic(60_000, 15_000, "P1_STATE", true),
        "PUMP_GLOBALS" to topic(60_000, 15_000, "P1_STATE", true),
        "CGM" to topic(300_000, 15_000, "P1_STATE", false, null, 300_000, "P0_FLOOR"),
        "HOME_MIRROR" to topic(60_000, 15_000, "P2_DECORATIVE", true),
        "STATUS_BATCH" to topic(300_000, 60_000, "P1_STATE", false),
        "HISTORY" to topic(300_000, 60_000, "P2_DECORATIVE", false),
        "BOLUS_OUTCOME" to topic(1_000, 1_000, "P0_CONFIRM", false, 60_000),
        // schema v2 起改按数据域：首页常驻字段全部带保底，页面批逐步迁走。
        "PUMP_VITALS" to topic(60_000, 15_000, "P0_FLOOR", false, null, 60_000, "P0_FLOOR"),
        "IOB" to topic(60_000, 15_000, "P0_FLOOR", false, null, 60_000, "P0_FLOOR"),
        "BASAL" to topic(120_000, 15_000, "P0_FLOOR", false, null, 120_000, "P0_FLOOR"),
        "BOLUS_LAST" to topic(120_000, 15_000, "P0_FLOOR", false, null, 120_000, "P0_FLOOR"),
    )

    val DEFAULT_FRESHNESS = FreshnessPolicy(30_000, 180_000, 900_000)

    val DEFAULT_STATES = StatesPolicy(
        connectedSettleMs = 8_000,
        linkSilentAfterMs = 30_000,
        visibilityDebounceMs = 1_000,
        skipCgmTopicWhenSessionStopped = true,
        skipCiqTopicsWhenClosedLoopOff = true,
        bolusSessionPollMs = 10_000,
        extendedSessionPollMs = 300_000,
    )

    val DEFAULT_BUDGET = BudgetPolicy(
        perMinute = 10,
        burst = 20,
        burstWindowMs = 60_000,
        degradeOrder = listOf("P2_DECORATIVE", "P1_STATE"),
        protectPriority = listOf("P0_FLOOR", "P0_CONFIRM"),
    )

    val DEFAULT_EVENTS: Map<String, EventPolicy> = linkedMapOf(
        "IOB_CHANGE" to EventPolicy(listOf("IOB"), 60_000),
        "BOLUS_CHANGE" to EventPolicy(listOf("BOLUS_OUTCOME", "ACTIONS"), 5_000),
        "CGM_CHANGE" to EventPolicy(listOf("CGM"), 5_000),
        "ALERT_CHANGE" to EventPolicy(listOf("NOTIFICATIONS"), 5_000),
    )

    val DEFAULT_RECONNECT = ReconnectPolicy(listOf("STATUS_BATCH"), 3_000, 15_000, 5_000, 2)

    /** S5 缺省值：与设计 §14 的实测阈值一致；配置缺段时按这套生效。 */
    val DEFAULT_FLAP = FlapPolicy()

    val DEFAULT_SWITCHES = SwitchesPolicy(true, true, true, true)

    /** 优先级集合。P0_FLOOR 是保底档专用，只有带 floorIntervalMs 的域会用它。 */
    private val PRIORITIES = setOf("P0_FLOOR", "P0_CONFIRM", "P1_STATE", "P2_DECORATIVE")

    /**
     * 静态清单：截至第 0 步仍硬编码在代码里的策略值（对应 §4.5.1 E1 / §10.1 D3）。
     * 不是运行时扫描——B/C 步迁完一项就删一行，为空才算"策略真的外置了"。
     *
     * 2026-11-12（S4）：十个 mobile 页面的「60 s 刷新」已全部迁到查询器（订阅式 + 分档策略），
     * 相应条目从本清单移除；`BolusNotificationBroadcastReceiver` 的 1 s×60 也已随大剂量
     * 窗口化改造删除。剩下的是尚未迁移的真实硬编码，清单只描述现状，不作为验收依据。
     */
    private val STATIC_HARDCODED_POLICY = listOf(
        // wear 的 60 s 是「页面打开时的额外驱动」，不是唯一驱动：两侧各有 5 min 保底
        // （mobile `CommService.periodicUpdateTask` / wear `WearPumpCommService`）。且 wear 只依赖
        // :shared，读不到 :mobile 的 QueryPolicy——要按配置发查询得先有 wear 侧调度器。
        "wear/LandingScreen.kt:143 intervalOf(60) 页面打开时 60 s（保底 5 min 由两端的 periodicUpdateTask 发；待 wear 侧调度器 + 平台×型号配置）",
        "wear/BasalDetailScreen.kt:80 intervalOf(60) 同上",
        "wear/ProfileSwitchScreen.kt:70 intervalOf(60) 同上",
        "wear/WearPumpCommService.kt:115 周期任务 5 min（手表 PUMP_HOST 角色下的保底）",
        "PumpCommHandler.kt:72 CacheSeconds=30",
    )

    private fun fallbackPolicy(loadedAtMillis: Long): QueryPolicy = QueryPolicy(
        schemaVersion = 2,
        source = PolicySource.FALLBACK,
        hash = "fallback",
        loadedAtMillis = loadedAtMillis,
        topics = DEFAULT_TOPICS,
        freshness = DEFAULT_FRESHNESS,
        states = DEFAULT_STATES,
        budget = DEFAULT_BUDGET,
        events = DEFAULT_EVENTS,
        reconnect = DEFAULT_RECONNECT,
        switches = DEFAULT_SWITCHES,
        diagnostics = PolicyDiagnostics(
            missingKeys = emptyList(),
            clamped = emptyList(),
            typeMismatch = emptyList(),
            unknownKeys = emptyList(),
            ignoredKeys = emptyList(),
            rejected = emptyList(),
            hardcodedPolicyFound = STATIC_HARDCODED_POLICY,
        ),
        applyPendingOn = emptyList(),
    )

    // ------------------------------------------------------------------
    // /api/query/stats 的 policy 段
    // ------------------------------------------------------------------

    fun buildJson(p: QueryPolicy): JSONObject {
        val json = JSONObject()
        json.put("source", p.source.name)
        json.put("schemaVersion", p.schemaVersion)
        json.put("hash", p.hash)
        json.put("loadedAtMillis", p.loadedAtMillis)
        json.put("deviceClass", p.diagnostics.deviceClass)
        json.put("deviceClassSource", p.diagnostics.deviceClassSource)
        json.put("composedFrom", JSONArray(p.diagnostics.composedFrom))
        json.put("applyPendingOn", JSONArray(p.applyPendingOn))

        val effective = JSONObject()
        p.effectiveValues().forEach { (k, v) -> effective.put(k, v) }
        json.put("effective", effective)

        val diff = JSONArray()
        val defaultValues = fallbackPolicy(p.loadedAtMillis).effectiveValues()
        p.effectiveValues().forEach { (k, v) ->
            val def = defaultValues[k]
            if (def != v) diff.put("$k: $def -> $v")
        }
        json.put("diffFromDefault", diff)

        val diag = JSONObject()
        diag.put("missingKeys", JSONArray(p.diagnostics.missingKeys))
        diag.put("clamped", JSONArray(p.diagnostics.clamped))
        diag.put("typeMismatch", JSONArray(p.diagnostics.typeMismatch))
        diag.put("unknownKeys", JSONArray(p.diagnostics.unknownKeys))
        diag.put("ignoredKeys", JSONArray(p.diagnostics.ignoredKeys))
        diag.put("rejected", JSONArray(p.diagnostics.rejected))
        diag.put("hardcodedPolicyFound", JSONArray(p.diagnostics.hardcodedPolicyFound))
        json.put("diagnostics", diag)
        return json
    }
}
