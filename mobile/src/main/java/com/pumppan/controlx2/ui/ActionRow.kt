package com.pumppan.controlx2.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

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
 * @param readOnly 只读：不可点击；未激活时文字降透明度（激活态保持不透明，让「进行中」的状态看得清）
 * 高度随内容自适应，不引入固定高度。
 */
@Composable
fun ActionRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    emphasized: Boolean = false,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // 只读且未激活时才降透明度；激活态保持不透明，让「进行中」的状态看得清
    val dimmed = readOnly && !emphasized
    val titleColor = if (dimmed) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val subtitleColor = if (dimmed) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    BaseRow(
        title = title,
        titleStyle = rowTitleStyle(emphasized),
        titleColor = titleColor,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        leadingIcon = leadingIcon?.let { icon ->
            { CompositionLocalProvider(LocalContentColor provides titleColor) { icon() } }
        },
        onClick = if (readOnly) null else onClick,
        modifier = modifier,
    )
}
