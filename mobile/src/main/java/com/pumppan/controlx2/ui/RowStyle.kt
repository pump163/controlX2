package com.pumppan.controlx2.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 危险操作行（停止/启动胰岛素、临时基础率等）的最小行高，保证多行文案不被裁切。 */
val DangerousRowMinHeight = 88.dp

/**
 * 控制页行的三态视觉。
 *
 * - [UNCONTROLLABLE]：App 不能控的泵（X2），且功能未激活 → 半透明但可读
 * - [READY]：App 能控的泵（Mobi），功能未激活 → 不透明、字号字重正常
 * - [ACTIVE]：功能已激活，以泵上报状态为准（谁在泵上开的都一样）→ 取消半透明、放大加粗
 */
enum class RowVisualState {
    UNCONTROLLABLE,
    READY,
    ACTIVE,
}

/**
 * 不可控且未激活时的透明度。Material 默认禁用色 0.38 偏暗，
 * 这里取 0.6，与储药器菜单禁用文字的取值一致，保证仍可读。
 */
internal const val UncontrollableRowAlpha = 0.6f

/** 由「能否控制」「是否已激活」推导三态：激活优先，X2 上泵里开了也照样算 ACTIVE。 */
internal fun rowVisualState(controllable: Boolean, active: Boolean): RowVisualState = when {
    active -> RowVisualState.ACTIVE
    !controllable -> RowVisualState.UNCONTROLLABLE
    else -> RowVisualState.READY
}

/**
 * 行标题样式。激活态放大用「主题字号 × 比例」而不是写死 sp：
 * titleMedium 与 bodyLarge 同为 16sp，换档位起不到放大作用，
 * 按比例放大才能同时跟随系统字体缩放（fontScale）和换主题。
 */
@Composable
internal fun rowTitleStyle(state: RowVisualState): TextStyle {
    val base = MaterialTheme.typography.bodyLarge
    return if (state == RowVisualState.ACTIVE) {
        base.copy(fontWeight = FontWeight.Bold, fontSize = base.fontSize * 1.15f)
    } else {
        base
    }
}

@Composable
internal fun rowTitleColor(state: RowVisualState): Color = when (state) {
    RowVisualState.UNCONTROLLABLE ->
        MaterialTheme.colorScheme.onSurface.copy(alpha = UncontrollableRowAlpha)
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
internal fun rowSubtitleColor(state: RowVisualState): Color = when (state) {
    RowVisualState.UNCONTROLLABLE ->
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = UncontrollableRowAlpha)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 激活态且 App 可控时铺反色容器（浅色白底 / 深色黑底），文字用 onSurface 对应反色；
 * X2 上泵里已开但 App 不可控时不铺容器，只靠放大加粗提示。
 */
@Composable
internal fun rowContainerColor(state: RowVisualState, controllable: Boolean): Color =
    if (state == RowVisualState.ACTIVE && controllable) {
        MaterialTheme.colorScheme.surface
    } else {
        Color.Transparent
    }

/** 只读（X2 不能操作）时降到 38% 透明度，与 Material 禁用色一致。 */
@Composable
internal fun rowTitleColor(readOnly: Boolean): Color =
    if (readOnly) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    else MaterialTheme.colorScheme.onSurface

@Composable
internal fun rowSubtitleColor(readOnly: Boolean): Color =
    if (readOnly) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    else MaterialTheme.colorScheme.onSurfaceVariant
