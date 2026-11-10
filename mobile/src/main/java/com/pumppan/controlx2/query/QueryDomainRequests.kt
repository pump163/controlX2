package com.pumppan.controlx2.query

import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CGMStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ControlIQIOBRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ControlIQInfoV2Request
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBasalStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBatteryV2Request
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentEGVGuiDataRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.ExtendedBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.HomeScreenMirrorRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.InsulinStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.LastBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.TempRateRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.TimeSinceResetRequest

/**
 * 「一个数据域包含哪些请求」的唯一登记处。
 *
 * 这是「发现单发遗漏只改一处」里的那**一处**：之后新增/删减请求只改这里的 when，
 * 调用方（协调器、一次性任务、页面订阅）都不需要跟着动。
 *
 * 只登记**只读**请求；控制 / 签名 / 取消类不进这张表（它们必须独立下发）。
 */
object QueryDomainRequests {

    /** 未登记的域返回空列表；协调器会跳过空域，不会「发了个空包」。 */
    fun forDomain(domain: String): List<Message> = when (domain) {
        // 保底域：电池 / 储药 / 胰岛素 / 复位时长
        "PUMP_VITALS" -> listOf(
            CurrentBatteryV2Request(),
            InsulinStatusRequest(),
            TimeSinceResetRequest(),
        )

        // IOB：X2 上两种请求可互替，先固定用 ControlIQ 版
        "IOB" -> listOf(ControlIQIOBRequest())

        // 当前基础率：首页常驻卡片，保底
        "BASAL" -> listOf(CurrentBasalStatusRequest())

        // 上一次大剂量：首页常驻卡片，保底
        "BOLUS_LAST" -> listOf(LastBolusStatusRequest())

        // 血糖：CGM 会话/传感器状态 + EGV 图形值
        "CGM" -> listOf(
            CGMStatusRequest(),
            CurrentEGVGuiDataRequest(),
        )

        // CIQ 状态：闭环开关与当前工作态；频率可低，但不能不查
        "CONTROL_IQ" -> listOf(ControlIQInfoV2Request())

        // 运行态：暂停 / 运动 / 睡眠 / 临时基础率 / 闭环状态都在这一批里
        "PUMP_STATE" -> listOf(
            HomeScreenMirrorRequest(),
            ControlIQInfoV2Request(),
            TempRateRequest(),
        )

        // 在运行的大剂量：立即大剂量与延展大剂量各一条
        "BOLUS_OUTCOME" -> listOf(
            CurrentBolusStatusRequest(),
            ExtendedBolusStatusRequest(),
        )

        else -> emptyList()
    }

    fun isSupported(domain: String): Boolean = forDomain(domain).isNotEmpty()
}
