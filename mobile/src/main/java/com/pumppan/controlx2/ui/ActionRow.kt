package com.pumppan.controlx2.ui

import androidx.compose.material3.LocalContentColor
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
 * @param emphasized 已激活（以泵上报状态为准）：取消半透明、标题放大加粗
 * @param readOnly 不可控（X2）：未激活时半透明但可读；已激活时同样放大加粗提示
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
    val controllable = !readOnly
    val state = rowVisualState(controllable = controllable, active = emphasized)
    val titleColor = rowTitleColor(state)
    val subtitleColor = rowSubtitleColor(state)

    BaseRow(
        title = title,
        titleStyle = rowTitleStyle(state),
        titleColor = titleColor,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        leadingIcon = leadingIcon?.let { icon ->
            { CompositionLocalProvider(LocalContentColor provides titleColor) { icon() } }
        },
        onClick = if (readOnly) null else onClick,
        containerColor = rowContainerColor(state, controllable),
        modifier = modifier,
    )
}
