package com.jwoglom.controlx2.shared.util

import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel

/**
 * pumppan: 调试用的型号覆盖。非 null 时 [determinePumpModel] 一律返回它，
 * 让 UI 按另一型号渲染（只看不点，Mobi 专有操作在真实 X2 上可能出错）。
 * 置空即恢复按蓝牙名判定。进程级变量，由 PumppanApplication 在启动时灌入。
 */
@Volatile
var pumppanModelOverride: KnownDeviceModel? = null

// the pump sid is the short pump serial number -- the last 3 numeric characters --
// which is contained in the bluetooth device name like:
// - "tslim X2 ***123" (t:slim X2)
// - "Tandem Mobi 123" (Mobi)
fun extractPumpSid(pumpDeviceName: String): Int? {
    // 用真实型号解析，否则覆盖成另一型号后 SID 会解析错
    return when (determinePumpModelFromName(pumpDeviceName)) {
        KnownDeviceModel.TSLIM_X2 ->  pumpDeviceName.substringAfterLast("*").toIntOrNull()
        KnownDeviceModel.MOBI -> pumpDeviceName.substringAfterLast(" ").toIntOrNull()
        else -> null
    }
}

fun determinePumpModel(pumpDeviceName: String): KnownDeviceModel? =
    pumppanModelOverride ?: determinePumpModelFromName(pumpDeviceName)

fun determinePumpModelFromName(pumpDeviceName: String): KnownDeviceModel? {
    if (pumpDeviceName.startsWith("tslim X2 ***")) {
        return KnownDeviceModel.TSLIM_X2
    } else if (pumpDeviceName.startsWith("Tandem Mobi")) {
        return KnownDeviceModel.MOBI
    } else {
        return null
    }
}
