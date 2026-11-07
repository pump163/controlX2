package com.pumppan.controlx2.bolus.state

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import timber.log.Timber

/** 当前持久化信封版本。读到更高版本一律按未知会话处理，不自动发送、不自动取消。 */
const val BOLUS_SESSION_SCHEMA_VERSION = 1

private const val PREFS_NAME = "bolus-session"
private const val KEY_ENVELOPE = "envelope"

/** 信封种类。CHAIN 只保留可空字段，C 轮实现前不做任何逻辑。 */
enum class BolusSessionKind {
    SINGLE,
    CHAIN,
}

/**
 * 单笔会话的落盘块。字段只增不改；与运行时 [BolusDelivery] 的字段同名同义，
 * 但这里是**独立模型**，不直接复用运行时会话对象。
 */
data class BolusSingleSession(
    val bolusId: Int? = null,
    val state: String? = null,
    val shape: String? = null,
    val nowMilliunits: Long? = null,
    val laterMilliunits: Long? = null,
    val extendedSeconds: Long? = null,
    val source: String? = null,
    val startedAtWallClock: Long? = null,
    val expectedEndAtWallClock: Long? = null,
    val lastEvidenceAtWallClock: Long? = null,
    val hasImmediateLeg: Boolean? = null,
    val immediateLegDoneAt: Long? = null,
    val outcome: BolusSessionOutcome? = null,
    val finishedAt: Long? = null,
) {
    fun toDelivery(): BolusDelivery? {
        val stateName = state ?: return null
        val parsedState = BolusDeliveryState.entries.firstOrNull { it.name == stateName } ?: return null
        return BolusDelivery(
            state = parsedState,
            bolusId = bolusId,
            hasImmediateLeg = hasImmediateLeg ?: true,
            startedAtMillis = startedAtWallClock,
            expectedEndAtMillis = expectedEndAtWallClock,
            lastEvidenceAtMillis = lastEvidenceAtWallClock ?: 0L,
            immediateLegDoneAtMillis = immediateLegDoneAt,
            finishedAtMillis = finishedAt,
            outcome = outcome?.toOutcome() ?: BolusOutcome.Pending,
        )
    }

    fun toMeta(): BolusSessionMeta = BolusSessionMeta(
        shape = shape,
        nowMilliunits = nowMilliunits,
        laterMilliunits = laterMilliunits,
        extendedSeconds = extendedSeconds,
        source = source,
    )

    companion object {
        fun from(delivery: BolusDelivery, meta: BolusSessionMeta?): BolusSingleSession =
            BolusSingleSession(
                bolusId = delivery.bolusId,
                state = delivery.state.name,
                shape = meta?.shape ?: inferShape(delivery),
                nowMilliunits = meta?.nowMilliunits,
                laterMilliunits = meta?.laterMilliunits,
                extendedSeconds = meta?.extendedSeconds,
                source = meta?.source ?: SOURCE_PUMP,
                startedAtWallClock = delivery.startedAtMillis,
                expectedEndAtWallClock = delivery.expectedEndAtMillis,
                lastEvidenceAtWallClock = delivery.lastEvidenceAtMillis,
                hasImmediateLeg = delivery.hasImmediateLeg,
                immediateLegDoneAt = delivery.immediateLegDoneAtMillis,
                outcome = delivery.outcome.toSessionOutcome(),
                finishedAt = delivery.finishedAtMillis,
            )

        private fun inferShape(delivery: BolusDelivery): String? = when {
            delivery.expectedEndAtMillis == null -> "NORMAL"
            delivery.hasImmediateLeg -> "TWIN"
            else -> "EXTENDED"
        }
    }
}

/**
 * 链式预约大剂量（C 轮）预留块：**只留字段，不实现逻辑**。
 * 字段名与 `docs/feature/定时大剂量-设计.md` 对齐，后续实现时只填充、不改名。
 */
data class BolusChainSession(
    val chainId: String? = null,
    val chainCount: Int? = null,
    val chainIndex: Int? = null,
    val chainState: String? = null,
    val anchorWallClock: Long? = null,
    val deadlineWallClock: Long? = null,
    val abortReason: String? = null,
    val notifiedAt: Long? = null,
    val linkBolusId: Int? = null,
)

/**
 * 大剂量向导（B 轮）草稿预留块：**只留字段，不实现逻辑**。
 * `cgmReceivedAt` 用于将来判断 CGM 读数是否仍新鲜；B 轮落地前不读写。
 */
data class BolusWizardDraft(
    val step: String? = null,
    val carbsGrams: Int? = null,
    val bgMgDl: Int? = null,
    val useCgm: Boolean? = null,
    val cgmReceivedAt: Long? = null,
    val iobMilliunits: Long? = null,
)

/** 结局的落盘形态（与运行时 [BolusOutcome] 分离，避免 UI 枚举变化影响旧信封）。 */
data class BolusSessionOutcome(
    val kind: String,
    val error: String? = null,
    val statusId: Int? = null,
    val reasonId: Int? = null,
    val fromCancel: Boolean? = null,
) {
    fun toOutcome(): BolusOutcome = when (kind) {
        KIND_SUCCESS -> BolusOutcome.Success
        KIND_CANCELLED -> BolusOutcome.Cancelled
        KIND_FAILED -> BolusOutcome.Failed(
            error = BolusError.entries.firstOrNull { it.name == error } ?: BolusError.UNKNOWN,
            statusId = statusId,
            reasonId = reasonId,
            fromCancel = fromCancel ?: false,
        )
        else -> BolusOutcome.Pending
    }

    companion object {
        const val KIND_PENDING = "PENDING"
        const val KIND_SUCCESS = "SUCCESS"
        const val KIND_CANCELLED = "CANCELLED"
        const val KIND_FAILED = "FAILED"

        fun from(outcome: BolusOutcome): BolusSessionOutcome = when (outcome) {
            BolusOutcome.Pending -> BolusSessionOutcome(KIND_PENDING)
            BolusOutcome.Success -> BolusSessionOutcome(KIND_SUCCESS)
            BolusOutcome.Cancelled -> BolusSessionOutcome(KIND_CANCELLED)
            is BolusOutcome.Failed -> BolusSessionOutcome(
                kind = KIND_FAILED,
                error = outcome.error.name,
                statusId = outcome.statusId,
                reasonId = outcome.reasonId,
                fromCancel = outcome.fromCancel,
            )
        }
    }
}

private fun BolusOutcome.toSessionOutcome(): BolusSessionOutcome = BolusSessionOutcome.from(this)

/**
 * 持久化信封。`chain` / `wizardDraft` 仅保留可空字段；
 * 单笔块是唯一当前实现的部分（A 轮）。
 */
data class BolusSessionEnvelope(
    val schemaVersion: Int = BOLUS_SESSION_SCHEMA_VERSION,
    val kind: BolusSessionKind = BolusSessionKind.SINGLE,
    val updatedAtWallClock: Long = 0L,
    val single: BolusSingleSession? = null,
    val chain: BolusChainSession? = null,
    val wizardDraft: BolusWizardDraft? = null,
)

/** 本次发起的输入元数据（不进运行时状态机，只在落盘时作为 single 块的补字段）。 */
data class BolusSessionMeta(
    val shape: String? = null,
    val nowMilliunits: Long? = null,
    val laterMilliunits: Long? = null,
    val extendedSeconds: Long? = null,
    val source: String? = SOURCE_APP,
)

const val SOURCE_APP = "APP"
const val SOURCE_PUMP = "PUMP"

sealed interface BolusSessionLoad {
    data object None : BolusSessionLoad

    /** 读到更高版本：视为未知会话，不恢复、不发送、不取消。 */
    data class Unsupported(val schemaVersion: Int) : BolusSessionLoad

    data class Restored(val envelope: BolusSessionEnvelope) : BolusSessionLoad
}

/**
 * 信封读写。写入只在状态跃迁时发生（由 [BolusSessionStore.onSessionChanged] 比较签名过滤），
 * 不随 ticker / 证据刷新落盘。
 */
class BolusSessionStore private constructor(
    private val prefs: SharedPreferences,
) {
    /** 最近一次落盘的会话签名；null = 当前没有已落盘的单笔会话。 */
    private var lastSignature: BolusDelivery? = null

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun load(): BolusSessionLoad {
        val raw = prefs.getString(KEY_ENVELOPE, null) ?: return BolusSessionLoad.None
        val envelope = runCatching { parseEnvelope(JSONObject(raw)) }.getOrNull()
            ?: return BolusSessionLoad.None
        if (envelope.schemaVersion > BOLUS_SESSION_SCHEMA_VERSION) {
            return BolusSessionLoad.Unsupported(envelope.schemaVersion)
        }
        return BolusSessionLoad.Restored(envelope)
    }

    /** 恢复后调用，避免把刚读出来的同一份状态再写一遍。 */
    fun markRestored(delivery: BolusDelivery?) {
        lastSignature = signatureOf(delivery)
    }

    fun onSessionChanged(delivery: BolusDelivery?, meta: BolusSessionMeta?) {
        val signature = signatureOf(delivery)
        if (signature == lastSignature) return
        if (delivery == null) {
            prefs.edit().remove(KEY_ENVELOPE).commit()
            lastSignature = null
            return
        }
        val envelope = BolusSessionEnvelope(
            schemaVersion = BOLUS_SESSION_SCHEMA_VERSION,
            kind = BolusSessionKind.SINGLE,
            updatedAtWallClock = System.currentTimeMillis(),
            single = BolusSingleSession.from(delivery, meta),
        )
        prefs.edit().putString(KEY_ENVELOPE, envelope.toJson().toString()).commit()
        lastSignature = signature
    }

    fun clear() {
        prefs.edit().remove(KEY_ENVELOPE).commit()
        lastSignature = null
    }

    private fun signatureOf(delivery: BolusDelivery?): BolusDelivery? = delivery?.copy(
        // 证据时间戳每次刷新都会变；不进签名，否则会退化成「随证据写盘」。
        lastEvidenceAtMillis = 0L,
        endObservedAtMillis = null,
    )
}

/** 信封 → JSON（字段只增不改；可空块/字段为空时不写出）。 */
fun BolusSessionEnvelope.toJson(): JSONObject = JSONObject().apply {
    put("schemaVersion", schemaVersion)
    put("kind", kind.name)
    put("updatedAtWallClock", updatedAtWallClock)
    single?.let { put("single", it.toJson()) }
    chain?.let { put("chain", it.toJson()) }
    wizardDraft?.let { put("wizardDraft", it.toJson()) }
}

private fun BolusSingleSession.toJson(): JSONObject = JSONObject().apply {
    bolusId?.let { put("bolusId", it) }
    state?.let { put("state", it) }
    shape?.let { put("shape", it) }
    nowMilliunits?.let { put("nowMilliunits", it) }
    laterMilliunits?.let { put("laterMilliunits", it) }
    extendedSeconds?.let { put("extendedSeconds", it) }
    source?.let { put("source", it) }
    startedAtWallClock?.let { put("startedAtWallClock", it) }
    expectedEndAtWallClock?.let { put("expectedEndAtWallClock", it) }
    lastEvidenceAtWallClock?.let { put("lastEvidenceAtWallClock", it) }
    hasImmediateLeg?.let { put("hasImmediateLeg", it) }
    immediateLegDoneAt?.let { put("immediateLegDoneAt", it) }
    outcome?.let { put("outcome", it.toJson()) }
    finishedAt?.let { put("finishedAt", it) }
}

private fun BolusChainSession.toJson(): JSONObject = JSONObject().apply {
    chainId?.let { put("chainId", it) }
    chainCount?.let { put("chainCount", it) }
    chainIndex?.let { put("chainIndex", it) }
    chainState?.let { put("chainState", it) }
    anchorWallClock?.let { put("anchorWallClock", it) }
    deadlineWallClock?.let { put("deadlineWallClock", it) }
    abortReason?.let { put("abortReason", it) }
    notifiedAt?.let { put("notifiedAt", it) }
    linkBolusId?.let { put("linkBolusId", it) }
}

private fun BolusWizardDraft.toJson(): JSONObject = JSONObject().apply {
    step?.let { put("step", it) }
    carbsGrams?.let { put("carbsGrams", it) }
    bgMgDl?.let { put("bgMgDl", it) }
    useCgm?.let { put("useCgm", it) }
    cgmReceivedAt?.let { put("cgmReceivedAt", it) }
    iobMilliunits?.let { put("iobMilliunits", it) }
}

private fun BolusSessionOutcome.toJson(): JSONObject = JSONObject().apply {
    put("kind", kind)
    error?.let { put("error", it) }
    statusId?.let { put("statusId", it) }
    reasonId?.let { put("reasonId", it) }
    fromCancel?.let { put("fromCancel", it) }
}

/** JSON → 信封。未知字段忽略；字段缺失保持可空。 */
fun parseEnvelope(json: JSONObject): BolusSessionEnvelope = BolusSessionEnvelope(
    schemaVersion = json.optInt("schemaVersion", 0),
    kind = enumOrNull<BolusSessionKind>(json.optString("kind")) ?: BolusSessionKind.SINGLE,
    updatedAtWallClock = json.optLong("updatedAtWallClock", 0L),
    single = json.optJSONObject("single")?.let(::parseSingle),
    chain = json.optJSONObject("chain")?.let(::parseChain),
    wizardDraft = json.optJSONObject("wizardDraft")?.let(::parseWizardDraft),
).also { envelope ->
    if (envelope.schemaVersion <= 0) {
        Timber.w("bolus: 信封缺少有效 schemaVersion，按未知会话处理")
    }
}

private fun parseSingle(obj: JSONObject): BolusSingleSession = BolusSingleSession(
    bolusId = obj.intOrNull("bolusId"),
    state = obj.stringOrNull("state"),
    shape = obj.stringOrNull("shape"),
    nowMilliunits = obj.longOrNull("nowMilliunits"),
    laterMilliunits = obj.longOrNull("laterMilliunits"),
    extendedSeconds = obj.longOrNull("extendedSeconds"),
    source = obj.stringOrNull("source"),
    startedAtWallClock = obj.longOrNull("startedAtWallClock"),
    expectedEndAtWallClock = obj.longOrNull("expectedEndAtWallClock"),
    lastEvidenceAtWallClock = obj.longOrNull("lastEvidenceAtWallClock"),
    hasImmediateLeg = obj.booleanOrNull("hasImmediateLeg"),
    immediateLegDoneAt = obj.longOrNull("immediateLegDoneAt"),
    outcome = obj.optJSONObject("outcome")?.let(::parseOutcome),
    finishedAt = obj.longOrNull("finishedAt"),
)

private fun parseChain(obj: JSONObject): BolusChainSession = BolusChainSession(
    chainId = obj.stringOrNull("chainId"),
    chainCount = obj.intOrNull("chainCount"),
    chainIndex = obj.intOrNull("chainIndex"),
    chainState = obj.stringOrNull("chainState"),
    anchorWallClock = obj.longOrNull("anchorWallClock"),
    deadlineWallClock = obj.longOrNull("deadlineWallClock"),
    abortReason = obj.stringOrNull("abortReason"),
    notifiedAt = obj.longOrNull("notifiedAt"),
    linkBolusId = obj.intOrNull("linkBolusId"),
)

private fun parseWizardDraft(obj: JSONObject): BolusWizardDraft = BolusWizardDraft(
    step = obj.stringOrNull("step"),
    carbsGrams = obj.intOrNull("carbsGrams"),
    bgMgDl = obj.intOrNull("bgMgDl"),
    useCgm = obj.booleanOrNull("useCgm"),
    cgmReceivedAt = obj.longOrNull("cgmReceivedAt"),
    iobMilliunits = obj.longOrNull("iobMilliunits"),
)

private fun parseOutcome(obj: JSONObject): BolusSessionOutcome = BolusSessionOutcome(
    kind = obj.optString("kind", BolusSessionOutcome.KIND_PENDING),
    error = obj.stringOrNull("error"),
    statusId = obj.intOrNull("statusId"),
    reasonId = obj.intOrNull("reasonId"),
    fromCancel = obj.booleanOrNull("fromCancel"),
)

private fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

private fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.longOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.booleanOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null

private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }
