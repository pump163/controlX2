package com.pumppan.controlx2.bolus.state

import android.content.Context
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel
import org.json.JSONObject

/** 单条命令/能力的支持状态（`supported` / `unverified` 等字符串原样保留）。 */
data class CommandCapability(
    val status: String,
    val note: String,
)

/**
 * 一个型号的能力档案。**唯一读取入口**是 [PumpCapabilities.forModel]：
 * UI 显隐、校验、发送前检查都从这里取，业务代码不写 `if (isX2)`。
 *
 * 数据来自 `assets/pump-capabilities.json`；文件缺失或解析失败时退回本文件内的兜底档案。
 */
data class PumpCapabilityProfile(
    val modelKey: String,
    /** 面向用户的型号显示名（配置 `displayName`）；取不到时 UI 回落成「当前型号」。 */
    val modelDisplayName: String? = null,
    val initiateNormalBolus: Boolean,
    val remoteExtendedBolusStart: Boolean,
    /**
     * `remoteExtendedBolusStart` 是否已真机验证。配置 `commands.verified`；
     * false 时 UI 显示「实验特性」提示，且发送时写入本地取证日志。
     */
    val remoteExtendedBolusVerified: Boolean,
    val extendedBolusStatusRead: Boolean,
    val extendedBolusCancel: Boolean,
    val permissionRelease: CommandCapability,
    val bolusIncrementMilliunits: Int,
    val minBolusMilliunits: Int,
    val minImmediateMilliunits: Int,
    /**
     * 延展段**客户端**兜底校验下限（毫单位）。不是泵的下限：Mobi 手册没有延展段最小剂量，
     * 唯一下限是整次大剂量 0.05U，且 Deliver Now 可设为 0。仅用于本地提示，不做协议裁决。
     */
    val clientMinExtendedCheckMilliunits: Int,
    /** 延展时长下限（秒）；UI 以分钟展示（默认 15 分钟）。 */
    val minExtendedSeconds: Int,
    val maxBolusMilliunits: Int,
    val maxExtendedSecondsCiq: Int,
    val maxExtendedSecondsNonCiq: Int,
    /**
     * 「此处做不了」的提示文案，可空。X2 =「请到泵本体操作」；Mobi 为 null（无屏、操作就在本应用内，
     * 不加任何说明噪音）。UI 只在非 null 时渲染一行提示。
     */
    val unsupportedHint: String?,
    val wireDurationUnit: String,
    val historyExtendedDurationUnit: String,
    val requestBitmask: Map<String, Int>,
    /**
     * 延展大剂量请求侧的实测掩码（Mobi 两条抓包均为 12，非唯一合法值）。
     * 发送层直接用它，**不要**用 `BolusType#toBitmask` 推导。
     */
    val extendedBitmask: Int,
    val historyBolusTypeBitmask: Map<String, Int>,
    /** 立即段查询间隔（§4.9.3）；默认 10 秒。 */
    val immediatePollIntervalMs: Long = 10_000L,
    /** 延展段兜底查询间隔（§4.9.3）；默认 5 分钟。 */
    val extendedPollIntervalMs: Long = 300_000L,
    /** 同一 opCode 在飞请求的超时（§4.9.3）；超时后才允许重发。 */
    val requestTimeoutMs: Long = 10_000L,
    /** 确认阶梯第一档：发出 `-98` 后多久未确认转 UNCONFIRMED。 */
    val confirmUnconfirmedMs: Long = 10_000L,
    /** 确认阶梯第二档：发出 `-98` 后多久仍无证据落 UNKNOWN。 */
    val confirmUnknownMs: Long = 60_000L,
    /** 取消确认窗口：发出 `-96` 后多久仍无 `-95` / `-91` → 落 UNKNOWN（取消的终态兜底）。 */
    val cancelConfirmMs: Long = 10_000L,
    /** 取消节流窗口（15 秒内最多 maxCancelAttempts 次）。 */
    val cancelThrottleWindowMs: Long = 15_000L,
    val maxCancelAttempts: Int = 3,
    /** 无输注时会话痕迹的过期窗口。 */
    val staleNoBolusMs: Long = 600_000L,
    /** 立即段输注期的证据过期窗口（3× 10 秒轮询）。 */
    val staleImmediateMs: Long = 30_000L,
    /** 延展段过了预计结束时刻后的兜底窗口。 */
    val staleExtendedGraceMs: Long = 300_000L,
    /** V2 常驻通知的重绘间隔。 */
    val notificationRefreshIntervalMs: Long = 1_000L,
    /** 结局页自动关闭时长。 */
    val endingPageDismissMs: Long = 15_000L,
    /** 百分比快捷项默认值（仅作为 Prefs 的首次默认，之后以用户编辑为准）。 */
    val extendedPercentPresets: List<Int> = listOf(0, 50, 75),
    /** 向导（大剂量计算器）的阈值与上报开关；配置缺失时用 [WizardConfig.Default]。 */
    val wizard: WizardConfig = WizardConfig.Default,
) {
    fun maxExtendedSeconds(ciqEnabled: Boolean?): Int =
        if (ciqEnabled == false) maxExtendedSecondsNonCiq else maxExtendedSecondsCiq
}

/**
 * `assets/pump-capabilities.json` 的加载器与访问器。
 *
 * - 进程内只解析一次（[initialize] 于 `PumppanApplication.onCreate` 调用），之后全部走缓存。
 * - 文件缺失 / 解析异常 → 使用代码内的兜底档案，不崩、不阻断启动。
 * - 型号键与 pumpx2 的 [KnownDeviceModel] 同名（`TSLIM_X2` / `MOBI`），未命中走 `default`。
 */
object PumpCapabilities {

    const val ASSET_NAME = "pump-capabilities.json"

    private const val X2 = "TSLIM_X2"
    private const val MOBI = "MOBI"
    private const val DEFAULT = "default"

    /** 兜底：与配置 `default` 一致（步进 25，扩展发起按未知处理为 true）。 */
    private val FALLBACK_DEFAULT = PumpCapabilityProfile(
        modelKey = DEFAULT,
        initiateNormalBolus = true,
        remoteExtendedBolusStart = true,
        remoteExtendedBolusVerified = true,
        extendedBolusStatusRead = true,
        extendedBolusCancel = true,
        permissionRelease = CommandCapability("unverified", "2026-11-06 未发 -16，保持未知语义"),
        bolusIncrementMilliunits = 25,
        minBolusMilliunits = 50,
        minImmediateMilliunits = 50,
        clientMinExtendedCheckMilliunits = 400,
        minExtendedSeconds = 900,
        maxBolusMilliunits = 25_000,
        maxExtendedSecondsCiq = 7_200,
        maxExtendedSecondsNonCiq = 28_800,
        unsupportedHint = null,
        wireDurationUnit = "seconds",
        historyExtendedDurationUnit = "minutes",
        requestBitmask = mapOf("FOOD1" to 1, "CORRECTION" to 2, "EXTENDED" to 4, "FOOD2" to 8),
        extendedBitmask = 12,
        historyBolusTypeBitmask = mapOf(
            "NOW" to 1, "LATER" to 2, "OVERRIDE" to 4, "CORRECTION" to 8, "CARB" to 16,
        ),
        immediatePollIntervalMs = 10_000L,
        extendedPollIntervalMs = 300_000L,
    )

    /** 兜底：t:slim X2（已真机定论，见 HTTP取证与真机点检.md §9.9）。 */
    private val FALLBACK_X2 = FALLBACK_DEFAULT.copy(
        modelKey = X2,
        modelDisplayName = "t:slim X2",
        remoteExtendedBolusStart = false,
        bolusIncrementMilliunits = 50,
        // X2 有屏，延展只能在泵本体发起；Mobi 无屏，故为 null（不渲染任何提示行）。
        unsupportedHint = "请到泵本体操作",
    )

    /** 兜底：Mobi（接受纯方波，未真机验证）。 */
    private val FALLBACK_MOBI = FALLBACK_DEFAULT.copy(
        modelKey = MOBI,
        modelDisplayName = "Mobi",
        remoteExtendedBolusStart = true,
        remoteExtendedBolusVerified = false,
        bolusIncrementMilliunits = 50,
        minImmediateMilliunits = 0,
    )

    @Volatile
    private var defaultProfile: PumpCapabilityProfile = FALLBACK_DEFAULT

    @Volatile
    private var modelProfiles: Map<String, PumpCapabilityProfile> = emptyMap()

    /** 在 `Application.onCreate` 调用一次；失败静默退回兜底，不影响启动。 */
    fun initialize(context: Context) {
        val raw = runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        runCatching { applyJson(raw) }
    }

    /** 解析整份配置并替换缓存；异常由调用方吞掉（[initialize]）。 */
    internal fun applyJson(raw: String) {
        val root = JSONObject(raw)
        val fallback = root.optJSONObject("default")
            ?.let { parseProfile(it, DEFAULT, FALLBACK_DEFAULT) }
            ?: FALLBACK_DEFAULT
        val modelsObj = root.optJSONObject("models")
        val models = if (modelsObj == null) {
            emptyMap()
        } else {
            modelsObj.keys().asSequence().associateWith { key ->
                parseProfile(modelsObj.getJSONObject(key), key, fallback)
            }
        }
        defaultProfile = fallback
        modelProfiles = models
    }

    fun forModel(model: KnownDeviceModel?): PumpCapabilityProfile {
        val key = model?.name
        if (key != null) {
            modelProfiles[key]?.let { return it }
            return when (key) {
                X2 -> FALLBACK_X2
                MOBI -> FALLBACK_MOBI
                else -> defaultProfile
            }
        }
        return defaultProfile
    }

    private fun parseProfile(
        obj: JSONObject,
        key: String,
        base: PumpCapabilityProfile,
    ): PumpCapabilityProfile {
        val commands = obj.optJSONObject("commands")
        val release = commands?.optJSONObject("bolusPermissionRelease")
        val limits = obj.optJSONObject("limits")
        val ui = obj.optJSONObject("ui")
        val units = obj.optJSONObject("units")
        val encodings = obj.optJSONObject("encodings")
        val polling = obj.optJSONObject("polling")
        val timing = obj.optJSONObject("timing")
        val notifications = obj.optJSONObject("notifications")
        val wizard = obj.optJSONObject("wizard")

        return PumpCapabilityProfile(
            modelKey = key,
            modelDisplayName = readNullableString(obj, "displayName", base.modelDisplayName),
            initiateNormalBolus = commands?.optBoolean("initiateNormalBolus", base.initiateNormalBolus)
                ?: base.initiateNormalBolus,
            remoteExtendedBolusStart = commands
                ?.optBoolean("remoteExtendedBolusStart", base.remoteExtendedBolusStart)
                ?: base.remoteExtendedBolusStart,
            remoteExtendedBolusVerified = commands
                ?.optBoolean("verified", base.remoteExtendedBolusVerified)
                ?: base.remoteExtendedBolusVerified,
            extendedBolusStatusRead = commands
                ?.optBoolean("extendedBolusStatusRead", base.extendedBolusStatusRead)
                ?: base.extendedBolusStatusRead,
            extendedBolusCancel = commands
                ?.optBoolean("extendedBolusCancel", base.extendedBolusCancel)
                ?: base.extendedBolusCancel,
            permissionRelease = CommandCapability(
                status = release?.optString("status", base.permissionRelease.status)
                    ?: base.permissionRelease.status,
                note = release?.optString("note", base.permissionRelease.note)
                    ?: base.permissionRelease.note,
            ),
            bolusIncrementMilliunits = limits
                ?.optInt("bolusIncrementMilliunits", base.bolusIncrementMilliunits)
                ?: base.bolusIncrementMilliunits,
            minBolusMilliunits = limits?.optInt("minBolusMilliunits", base.minBolusMilliunits)
                ?: base.minBolusMilliunits,
            minImmediateMilliunits = limits
                ?.optInt("minImmediateMilliunits", base.minImmediateMilliunits)
                ?: base.minImmediateMilliunits,
            clientMinExtendedCheckMilliunits = limits
                ?.optInt("clientMinExtendedCheck", base.clientMinExtendedCheckMilliunits)
                ?: base.clientMinExtendedCheckMilliunits,
            minExtendedSeconds = limits
                ?.optInt("minExtendedSeconds", base.minExtendedSeconds)
                ?: base.minExtendedSeconds,
            maxBolusMilliunits = limits?.optInt("maxBolusMilliunits", base.maxBolusMilliunits)
                ?: base.maxBolusMilliunits,
            unsupportedHint = readNullableString(ui, "unsupportedHint", base.unsupportedHint),
            maxExtendedSecondsCiq = limits
                ?.optInt("maxExtendedSecondsCiq", base.maxExtendedSecondsCiq)
                ?: base.maxExtendedSecondsCiq,
            maxExtendedSecondsNonCiq = limits
                ?.optInt("maxExtendedSecondsNonCiq", base.maxExtendedSecondsNonCiq)
                ?: base.maxExtendedSecondsNonCiq,
            wireDurationUnit = units?.optString("wireDuration", base.wireDurationUnit)
                ?: base.wireDurationUnit,
            historyExtendedDurationUnit = units
                ?.optString("historyExtendedDurationRequested", base.historyExtendedDurationUnit)
                ?: base.historyExtendedDurationUnit,
            requestBitmask = readEncoding(encodings, "requestBitmask", base.requestBitmask),
            extendedBitmask = encodings?.optJSONObject("extendedBitmask")
                ?.optInt("value", base.extendedBitmask)
                ?: base.extendedBitmask,
            historyBolusTypeBitmask = readEncoding(
                encodings, "historyBolusTypeBitmask", base.historyBolusTypeBitmask,
            ),
            immediatePollIntervalMs = polling
                ?.optLong("immediatePollIntervalMs", base.immediatePollIntervalMs)
                ?: base.immediatePollIntervalMs,
            extendedPollIntervalMs = polling
                ?.optLong("extendedPollIntervalMs", base.extendedPollIntervalMs)
                ?: base.extendedPollIntervalMs,
            requestTimeoutMs = polling
                ?.optLong("requestTimeoutMs", base.requestTimeoutMs)
                ?: base.requestTimeoutMs,
            confirmUnconfirmedMs = timing
                ?.optLong("confirmUnconfirmedMs", base.confirmUnconfirmedMs)
                ?: base.confirmUnconfirmedMs,
            confirmUnknownMs = timing
                ?.optLong("confirmUnknownMs", base.confirmUnknownMs)
                ?: base.confirmUnknownMs,
            cancelConfirmMs = timing
                ?.optLong("cancelConfirmMs", base.cancelConfirmMs)
                ?: base.cancelConfirmMs,
            cancelThrottleWindowMs = timing
                ?.optLong("cancelThrottleWindowMs", base.cancelThrottleWindowMs)
                ?: base.cancelThrottleWindowMs,
            maxCancelAttempts = timing
                ?.optInt("maxCancelAttempts", base.maxCancelAttempts)
                ?: base.maxCancelAttempts,
            staleNoBolusMs = timing
                ?.optLong("staleNoBolusMs", base.staleNoBolusMs)
                ?: base.staleNoBolusMs,
            staleImmediateMs = timing
                ?.optLong("staleImmediateMs", base.staleImmediateMs)
                ?: base.staleImmediateMs,
            staleExtendedGraceMs = timing
                ?.optLong("staleExtendedGraceMs", base.staleExtendedGraceMs)
                ?: base.staleExtendedGraceMs,
            notificationRefreshIntervalMs = notifications
                ?.optLong("refreshIntervalMs", base.notificationRefreshIntervalMs)
                ?: base.notificationRefreshIntervalMs,
            endingPageDismissMs = ui
                ?.optLong("endingPageDismissMs", base.endingPageDismissMs)
                ?: base.endingPageDismissMs,
            extendedPercentPresets = readIntList(
                ui, "extendedPercentPresets", base.extendedPercentPresets,
            ),
            wizard = parseWizard(wizard, base.wizard),
        )
    }

    /** 读取 `wizard` 段；缺键沿用兜底，不写死任何阈值。 */
    private fun parseWizard(obj: JSONObject?, base: WizardConfig): WizardConfig {
        if (obj == null) return base
        val finger = obj.optJSONObject("fingerstick")
        val cgm = obj.optJSONObject("cgmPrefill")
        val bySensor = mutableMapOf<String, CgmPrefillRange>()
        cgm?.optJSONObject("bySensor")?.let { sensors ->
            sensors.keys().forEach { key ->
                sensors.optJSONObject(key)?.let {
                    bySensor[key.uppercase()] = parseCgmRange(it, base.cgmPrefillDefault)
                }
            }
        }
        val fingerBase = base.fingerstick
        return WizardConfig(
            enabled = obj.optBoolean("enabled", base.enabled),
            maxCarbsGrams = obj.optInt("maxCarbsGrams", base.maxCarbsGrams),
            fingerstick = FingerstickRange(
                mgdlMin = finger?.optInt("mgdlMin", fingerBase.mgdlMin) ?: fingerBase.mgdlMin,
                mgdlMax = finger?.optInt("mgdlMax", fingerBase.mgdlMax) ?: fingerBase.mgdlMax,
                mmolMin = finger?.optDouble("mmolMin", fingerBase.mmolMin) ?: fingerBase.mmolMin,
                mmolMax = finger?.optDouble("mmolMax", fingerBase.mmolMax) ?: fingerBase.mmolMax,
            ),
            cgmPrefillDefault = cgm?.optJSONObject("default")
                ?.let { parseCgmRange(it, base.cgmPrefillDefault) }
                ?: base.cgmPrefillDefault,
            cgmPrefillBySensor = bySensor,
            lowBgMgdl = obj.optInt("lowBgMgdl", base.lowBgMgdl),
            snapshotStaleMillis = obj.optLong("snapshotStaleMillis", base.snapshotStaleMillis),
            cgmStaleMillis = obj.optLong("cgmStaleMillis", base.cgmStaleMillis),
            iobStaleAfterMs = obj.optLong("iobStaleAfterMs", base.iobStaleAfterMs),
            recentInfusionVetoMillis = obj.optLong(
                "recentInfusionVetoMillis",
                base.recentInfusionVetoMillis,
            ),
            fetchThrottleMillis = obj.optLong("fetchThrottleMillis", base.fetchThrottleMillis),
            reportCarbsToPump = obj.optBoolean("reportCarbsToPump", base.reportCarbsToPump),
            reportBgToPump = obj.optBoolean("reportBgToPump", base.reportBgToPump),
            reportIobToPump = obj.optBoolean("reportIobToPump", base.reportIobToPump),
        )
    }

    private fun parseCgmRange(obj: JSONObject, base: CgmPrefillRange): CgmPrefillRange =
        CgmPrefillRange(
            minMgdl = obj.optInt("minMgdl", base.minMgdl),
            maxMgdl = obj.optInt("maxMgdl", base.maxMgdl),
        )

    private fun readIntList(obj: JSONObject?, key: String, base: List<Int>): List<Int> {
        val array = obj?.optJSONArray(key) ?: return base
        val parsed = (0 until array.length()).map { array.optInt(it) }
        return if (parsed.isEmpty()) base else parsed
    }

    /**
     * 读可空字符串：键不存在 → 用兜底；显式 `null` → 返回 null（用于「不渲染提示」）。
     * 注意 `JSONObject.optString` 会把 JSON null 变成字符串 "null"，不能直接用。
     */
    private fun readNullableString(obj: JSONObject?, key: String, base: String?): String? {
        if (obj == null || !obj.has(key)) return base
        val value = runCatching { obj.get(key) }.getOrNull()
        return if (value == null || value == JSONObject.NULL) null else value.toString()
    }

    private fun readEncoding(
        encodings: JSONObject?,
        key: String,
        base: Map<String, Int>,
    ): Map<String, Int> {
        val values = encodings?.optJSONObject(key)?.optJSONObject("values") ?: return base
        val parsed = values.keys().asSequence().associateWith { values.optInt(it) }
        return if (parsed.isEmpty()) base else parsed
    }
}
