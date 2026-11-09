package com.pumppan.controlx2.bolus

import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.control.CancelBolusRequest
import com.pumppan.controlx2.bolus.state.CancelDecision

/**
 * 取消的唯一发送路径：大剂量页段三与底栏细条都走这里。
 * 判定（禁盲取消 / 断连 fail-fast / 节流）在 [com.pumppan.controlx2.bolus.state.BolusSessionHolder.requestCancel]，
 * 这里只负责「判定为可发时用同一种方式发 `-96`」，避免两个入口各写一份发送逻辑。
 */
internal fun BolusEntryCoordinator.performCancel(
    connected: Boolean,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
): CancelDecision {
    val decision = holder.requestCancel(connected = connected)
    if (decision is CancelDecision.Send) {
        sendPumpCommands(SendType.BUST_CACHE, listOf(CancelBolusRequest(decision.bolusId)))
    }
    return decision
}
