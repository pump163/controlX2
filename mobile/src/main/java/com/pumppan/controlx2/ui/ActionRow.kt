package com.pumppan.controlx2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 操作行：标题 + 副标题，点击执行动作（不跳页面）。
 *
 * 组件内不含任何文案，标题/副标题都由调用方传 stringResource。
 *
 * @param title 行标题
 * @param onClick 点击回调；readOnly=true 时不调用
 * @param subtitle 可选副标题（说明文字或当前状态）
 * @param leadingIcon 可选前置图标
 * @param emphasized 激活态：标题加粗并按主题字号放大
 * @param readOnly 只读：不可点击、文字降透明度
 * @param danger 危险操作（停止胰岛素/停止临时基础率）：标题与图标用 error 色、左侧 4dp 竖条 +
 *   淡红底；readOnly 时整体降一档但仍保留红色，用于区分「执行中但暂不可操作」与普通禁用行。
 *   高度仍为 heightIn(min=...)，随内容自适应，不引入固定高度。
 */
@Composable
fun ActionRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    emphasized: Boolean = false,
    readOnly: Boolean = false,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val errorColor = MaterialTheme.colorScheme.error
    val titleColor = when {
        danger && readOnly -> errorColor.copy(alpha = 0.72f)
        danger -> errorColor
        readOnly -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val subtitleColor = when {
        danger && readOnly -> errorColor.copy(alpha = 0.60f)
        danger -> errorColor.copy(alpha = 0.85f)
        readOnly -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val barColor = if (danger) {
        errorColor.copy(alpha = if (readOnly) 0.45f else 1f)
    } else {
        Color.Transparent
    }
    val rowBackground = if (danger) {
        errorColor.copy(alpha = if (readOnly) 0.06f else 0.12f)
    } else {
        Color.Transparent
    }

    ListItem(
        headlineContent = {
            Text(
                text = title,
                color = titleColor,
                style = rowTitleStyle(emphasized),
            )
        },
        supportingContent = subtitle?.let { text ->
            {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = subtitleColor,
                )
            }
        },
        leadingContent = leadingIcon?.let { icon ->
            {
                CompositionLocalProvider(LocalContentColor provides titleColor) { icon() }
            }
        },
        modifier = modifier
            .heightIn(min = 56.dp)
            .background(rowBackground)
            .drawBehind {
                if (danger) {
                    drawRect(color = barColor, size = Size(4.dp.toPx(), size.height))
                }
            }
            .clickable(enabled = !readOnly, onClick = onClick),
    )
}
