package com.pumppan.controlx2.scheduled

import com.pumppan.controlx2.bolus.CURRENT_BOLUS_DELIVERING
import com.pumppan.controlx2.bolus.CURRENT_BOLUS_REQUESTING

import android.content.Context
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.presentation.DataStore
import com.jwoglom.controlx2.shared.MessagePaths
import com.jwoglom.controlx2.shared.PumpMessageSerializer
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.control.BolusPermissionRequest
import com.jwoglom.pumpx2.pump.messages.request.control.InitiateBolusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ExtendedBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.response.control.BolusPermissionResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse
import kotlinx.coroutines.delay

/**
 * 定时大剂量的「发送」与「执行前置检查」。
 *
 * 只做两件事：拿 `-94` 的 bolusId 并发出普通 `-98`；以及在发第一笔之前核对三项状态。
 * **不复用** `BolusFlowScreen` 的确认页，因为那条链路会再次弹二次确认，
 * 而定时链的确认已经在「确认页 + 执行键」完成。
 */
object ScheduledBolusSender {

    data class Preflight(
        val linkFresh: Boolean,
        /** 泵上已有另一笔立即大剂量在进行。 */
        val foreignBolus: Boolean,
        val extendedActive: Boolean,
        /** 查询超时 / 泵不支持：按用户拍板交给界面弹「继续 / 终止」。 */
        val extendedUnknown: Boolean,
        val lastMessageAgeMs: Long?,
    )

    sealed interface SendResult {
        data class Sent(val bolusId: Int) : SendResult
        data class Failed(val reasonRes: Int) : SendResult
    }

    private const val PERMISSION_POLL_MS = 200L
    private const val PERMISSION_TIMEOUT_MS = 30_000L
    private const val PREFLIGHT_TIMEOUT_MS = 6_000L

    /**
     * 执行前检查：先各发一次只读查询，再按回来的新鲜度判定。
     * 链路容忍度来自 [ScheduledBolusConfig.preflightLinkFreshMs]（默认 15s）。
     */
    suspend fun preflight(
        context: Context,
        dataStore: DataStore,
        sendPumpCommands: (SendType, List<Message>) -> Unit,
    ): Preflight {
        val config = ScheduledBolusConfig.load(context)
        val beforeStatus = dataStore.bolusCurrentResponse.value
        val beforeExtendedAt = dataStore.extendedBolusUpdatedAtMillis.value

        sendPumpCommands(
            SendType.BUST_CACHE,
            listOf(CurrentBolusStatusRequest(), ExtendedBolusStatusRequest()),
        )

        val deadline = System.currentTimeMillis() + PREFLIGHT_TIMEOUT_MS
        var statusSeen: CurrentBolusStatusResponse? = null
        var extendedSeen = false
        while (System.currentTimeMillis() < deadline) {
            val current = dataStore.bolusCurrentResponse.value
            if (current != null && current !== beforeStatus) statusSeen = current
            if (dataStore.extendedBolusUpdatedAtMillis.value != beforeExtendedAt) extendedSeen = true
            if (statusSeen != null && extendedSeen) break
            delay(PERMISSION_POLL_MS)
        }

        val lastAt = dataStore.pumpLastMessageTimestamp.value?.toEpochMilli()
        val age = lastAt?.let { System.currentTimeMillis() - it }
        val fresh = dataStore.pumpConnected.value == true &&
            age != null && age <= config.preflightLinkFreshMs

        val status = statusSeen ?: dataStore.bolusCurrentResponse.value
        // 1 = DELIVERING，2 = REQUESTING；0 = 没有进行中的大剂量（可能回放上一笔的 id）。
        val foreign = status != null && status.bolusId > 0 &&
            (status.statusId == CURRENT_BOLUS_DELIVERING || status.statusId == CURRENT_BOLUS_REQUESTING)

        // 延展：泵侧只维护「是否有延展在跑」+ 更新时间；更新了才算查询成功。
        val active = extendedSeen && dataStore.extendedBolusActive.value == true
        val unknown = !extendedSeen

        return Preflight(
            linkFresh = fresh,
            foreignBolus = foreign,
            extendedActive = active,
            extendedUnknown = unknown,
            lastMessageAgeMs = age,
        )
    }

    /**
     * 发一笔普通立即大剂量。剂量单位是毫单位（1U = 1000）。
     * 成功只代表 `-98` 已交出去，不代表泵已开始。
     */
    suspend fun sendBolus(
        context: Context,
        dataStore: DataStore,
        sendPumpCommands: (SendType, List<Message>) -> Unit,
        sendMessage: (String, ByteArray) -> Unit,
        doseMilliunits: Long,
    ): SendResult {
        val before = dataStore.bolusPermissionResponse.value
        sendPumpCommands(SendType.BUST_CACHE, listOf(BolusPermissionRequest()))

        var waited = 0L
        var granted: BolusPermissionResponse? = null
        while (waited < PERMISSION_TIMEOUT_MS) {
            delay(PERMISSION_POLL_MS)
            waited += PERMISSION_POLL_MS
            val current = dataStore.bolusPermissionResponse.value
            if (current != null && current !== before) {
                granted = current
                break
            }
        }

        val permission = granted
        if (permission == null || !permission.isPermissionGranted || permission.bolusId <= 0) {
            return SendResult.Failed(R.string.bolus_v2_send_permission_failed)
        }

        // 普通大剂量：FOOD2 掩码沿用既有行为；carbs/bg/iob 一律 0，不在泵上留医疗记录。
        val bitmask = 8
        val bytes = runCatching {
            PumpMessageSerializer.toBytes(
                InitiateBolusRequest(
                    doseMilliunits,
                    permission.bolusId,
                    bitmask,
                    0L,
                    0L,
                    0,
                    0,
                    0L,
                ),
            )
        }.getOrElse { return SendResult.Failed(R.string.bolus_v2_send_build_failed) }

        sendMessage(MessagePaths.TO_SERVER_BOLUS_REQUEST_PHONE, bytes)
        return SendResult.Sent(permission.bolusId)
    }
}
