package com.pumppan.controlx2.bolus.send

import android.content.Context
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.response.control.InitiateBolusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ExtendedBolusStatusV2Response
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import timber.log.Timber

/**
 * Mobi 延展大剂量的**本地取证日志**（不上传）。
 *
 * 只在「能力未真机验证（`commands.verified=false`）且实际填了延展三字段」的发送时
 * [arm]；随后 [onMessage] 把 `-97` 响应与 `46` / `-74` 状态原值追加到同一份 jsonl 里，
 * 便于其他用户回传做真机验证。
 *
 * 位置：`getExternalFilesDir(null)/bolus-evidence/`（可用 `adb pull` 导出），
 * 外部存储不可用时退回 `filesDir/bolus-evidence/`。
 */
object BolusEvidenceLog {

    private const val DIR_NAME = "bolus-evidence"
    /** 发送后持续记多久的状态（毫秒）。 */
    private const val WATCH_WINDOW_MS = 180_000L

    @Volatile private var dir: File? = null
    @Volatile private var armedUntilMs = 0L
    @Volatile private var currentFile: File? = null
    @Volatile private var lastFile: File? = null

    fun initialize(context: Context) {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        dir = File(base, DIR_NAME).apply { mkdirs() }
    }

    /**
     * 写入请求行并进入监听窗口。返回日志路径；未命中（已验证型号 / 无延展段）时返回 null。
     */
    fun arm(
        plan: BolusSendPlan,
        bolusId: Int,
        rawRequestBytes: Int,
    ): String? {
        if (plan.extendedVerified || !plan.usesExtendedFields) return null
        val file = File(dir ?: return null, "mobi-extended-${stamp()}.jsonl")
        val record = JSONObject()
            .put("type", "request")
            .put("ts", stamp())
            .put("model", plan.modelKey)
            .put("capabilities", JSONObject()
                .put("remoteExtendedBolusStart", plan.remoteExtendedBolusStart)
                .put("verified", plan.extendedVerified)
                .put("minImmediateMilliunits", plan.minImmediateMilliunits)
                .put("clientMinExtendedCheckMilliunits", plan.clientMinExtendedCheckMilliunits)
                .put("maxExtendedSeconds", plan.maxExtendedSeconds)
            )
            .put("request", JSONObject()
                .put("totalVolumeMilliunits", plan.totalVolumeMilliunits)
                .put("bolusId", bolusId)
                .put("bolusTypeBitmask", plan.bolusTypeBitmask)
                .put("extendedVolumeMilliunits", plan.extendedVolumeMilliunits)
                .put("extendedSeconds", plan.extendedSeconds)
                .put("extended3", 0)
                .put("foodVolumeMilliunits", 0)
                .put("correctionVolumeMilliunits", 0)
                .put("wireBytes", rawRequestBytes)
            )
        append(file, record)
        currentFile = file
        lastFile = file
        armedUntilMs = System.currentTimeMillis() + WATCH_WINDOW_MS
        return file.absolutePath
    }

    /** 中央消息回调处调用；未 armed 时零开销直接返回。 */
    fun onMessage(message: Message) {
        val file = currentFile ?: return
        if (System.currentTimeMillis() > armedUntilMs) {
            currentFile = null
            armedUntilMs = 0L
            return
        }
        val record = when (message) {
            is InitiateBolusResponse -> JSONObject()
                .put("type", "response-97")
                .put("status", message.status)
                .put("statusTypeId", message.statusTypeId)
                .put("statusType", message.statusType?.name)
                .put("bolusId", message.bolusId)

            is ExtendedBolusStatusResponse -> JSONObject()
                .put("type", "status-46")
                .put("bolusStatus", message.bolusStatus)
                .put("bolusId", message.bolusId)
                .put("requestedVolume", message.requestedVolume)
                .put("durationMinutes", message.duration) // 历史侧单位=分钟
                .put("bolusSource", message.bolusSource)

            is ExtendedBolusStatusV2Response -> JSONObject()
                .put("type", "status-74")
                .put("bolusStatus", message.bolusStatus)
                .put("bolusId", message.bolusId)
                .put("requestedVolume", message.requestedVolume)
                .put("durationMinutes", message.duration) // 历史侧单位=分钟
                .put("bolusSource", message.bolusSource)

            else -> return
        }
        record.put("ts", stamp())
        append(file, record)
    }

    fun lastFilePath(): String? = lastFile?.absolutePath

    private fun append(file: File, record: JSONObject) {
        runCatching {
            file.appendText(record.toString() + "\n")
        }.onFailure {
            Timber.w(it, "BolusEvidenceLog: write failed")
        }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
