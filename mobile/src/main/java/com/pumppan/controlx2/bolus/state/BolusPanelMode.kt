package com.pumppan.controlx2.bolus.state

import kotlin.math.abs

/**
 * 大剂量页面的高度两档（不做连续拖拽）。
 *
 * - [FULL] 全屏：占满外层内容区。
 * - [HALF] 半屏：面板取可用高度的 [HALF_HEIGHT_FRACTION]（约 60%），锚定顶部，下方露出背景并压遮罩。
 *
 * 默认全屏；档位持久化在既有 prefs 里（`Prefs.bolusPanelMode`），读不到 / 值不认识时回默认。
 */
enum class BolusPanelMode(val prefValue: String) {
    FULL("full"),
    HALF("half");

    fun toggled(): BolusPanelMode = if (this == FULL) HALF else FULL

    /**
     * 拖动结束后按累计位移切档：向下拖（正值）→ 半屏，向上拖（负值）→ 全屏；
     * 未过阈值 → 保持当前档位（等价于点击）。
     */
    fun afterDrag(dragDeltaY: Float, threshold: Float): BolusPanelMode =
        if (abs(dragDeltaY) < threshold) this else if (dragDeltaY > 0) HALF else FULL

    companion object {
        /** 半屏占可用高度的比例。 */
        const val HALF_HEIGHT_FRACTION = 0.6f

        /** 首次进入 / 持久化缺失（进程重建、老版本升级）→ 全屏。 */
        fun fromPref(raw: String?): BolusPanelMode =
            values().firstOrNull { it.prefValue == raw } ?: FULL
    }
}
