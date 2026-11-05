package com.pumppan.controlx2.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

/**
 * 开关行：标题 + 可选副标题 + Switch。
 *
 * @param title 行标题
 * @param checked 开关状态（必须来自泵上报，X2 上也要能反映「泵里已开」）
 * @param onCheckedChange 状态变化回调；readOnly=true 时不调用
 * @param subtitle 可选副标题
 * @param leadingIcon 可选前置图标
 * @param emphasized 已激活：取消半透明、标题放大加粗
 * @param readOnly 不可控（X2）：未激活时整行半透明但可读；已激活时不透明并按真实开启态显示开关
 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    emphasized: Boolean = false,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val controllable = !readOnly
    // 激活以泵上报的 checked 为准，不看 emphasized：谁在泵上开的都要显示为激活
    val state = rowVisualState(controllable = controllable, active = checked)
    val titleColor = rowTitleColor(state)

    BaseRow(
        title = title,
        titleStyle = rowTitleStyle(state),
        titleColor = titleColor,
        subtitle = subtitle,
        subtitleColor = rowSubtitleColor(state),
        leadingIcon = leadingIcon?.let { icon ->
            {
                CompositionLocalProvider(LocalContentColor provides titleColor) { icon() }
            }
        },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = controllable,
                colors = switchColors(state, controllable),
            )
        },
        onClick = null,
        containerColor = rowContainerColor(state, controllable),
        modifier = modifier,
    )
}

/**
 * 开关配色跟随三态：
 * - 不可控且未激活：禁用态整体 0.6 透明度，与整行灰化一致
 * - 不可控但已激活：按真实开启态给出不透明主色，避免「开了却灰着」
 * - 可控：用默认配色
 */
@Composable
private fun switchColors(state: RowVisualState, controllable: Boolean) = when {
    !controllable && state == RowVisualState.ACTIVE -> SwitchDefaults.colors(
        disabledCheckedThumbColor = MaterialTheme.colorScheme.onPrimary,
        disabledCheckedTrackColor = MaterialTheme.colorScheme.primary,
        disabledCheckedBorderColor = MaterialTheme.colorScheme.primary,
        disabledCheckedIconColor = MaterialTheme.colorScheme.onPrimary,
    )
    !controllable -> SwitchDefaults.colors(
        disabledUncheckedThumbColor =
            MaterialTheme.colorScheme.onSurface.copy(alpha = UncontrollableRowAlpha),
        disabledUncheckedTrackColor =
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = UncontrollableRowAlpha),
        disabledUncheckedBorderColor =
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = UncontrollableRowAlpha),
        disabledUncheckedIconColor =
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = UncontrollableRowAlpha),
    )
    else -> SwitchDefaults.colors()
}
