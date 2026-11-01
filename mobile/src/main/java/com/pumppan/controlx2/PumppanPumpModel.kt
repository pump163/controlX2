package com.pumppan.controlx2

import android.content.Context
import com.jwoglom.controlx2.shared.util.pumppanModelOverride
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel

/**
 * 调试页：强制泵型号，用来查看另一型号才显示的设置项。
 * 只是让 UI 按该型号渲染，不改变真实泵通信。
 */
enum class PumppanPumpModel(val key: String, val model: KnownDeviceModel?) {
    AUTO("auto", null),
    MOBI("mobi", KnownDeviceModel.MOBI),
    SLIM_X2("slimx2", KnownDeviceModel.TSLIM_X2),
    ;

    companion object {
        fun fromKey(key: String?): PumppanPumpModel =
            values().firstOrNull { it.key == key } ?: AUTO

        /** 进程启动时把持久化的覆盖值灌进 shared 的全局开关。 */
        fun apply(context: Context) {
            pumppanModelOverride = PumppanPrefs.pumpModel(context).model
        }

        fun set(context: Context, mode: PumppanPumpModel) {
            PumppanPrefs.setPumpModel(context, mode)
            pumppanModelOverride = mode.model
        }
    }
}
