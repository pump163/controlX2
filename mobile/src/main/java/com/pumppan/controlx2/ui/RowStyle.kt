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
 * 行标题样式。放大用「主题字号 × 比例」而不是写死 sp，
 * 这样系统字体放大（fontScale）和换主题时都能跟着走。
 */
@Composable
internal fun rowTitleStyle(emphasized: Boolean): TextStyle {
    val base = MaterialTheme.typography.bodyLarge
    return if (emphasized) {
        base.copy(fontWeight = FontWeight.Bold, fontSize = base.fontSize * 1.15f)
    } else {
        base
    }
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
