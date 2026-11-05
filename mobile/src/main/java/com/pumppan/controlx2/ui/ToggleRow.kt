package com.pumppan.controlx2.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * 开关行：标题 + 可选副标题 + Switch。
 *
 * @param title 行标题
 * @param checked 开关状态
 * @param onCheckedChange 状态变化回调；readOnly=true 时不调用
 * @param subtitle 可选副标题
 * @param leadingIcon 可选前置图标
 * @param emphasized 激活态：标题加粗并按主题字号放大
 * @param readOnly 只读：开关禁用、文字降透明度，但 checked 仍实时反映真实状态
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
    // 控制页开关行不做只读降透明：激活与否都保持不透明，仅用字重区分激活态
    val titleColor = rowTitleColor(false)
    BaseRow(
        title = title,
        titleStyle = rowTitleStyle(emphasized),
        titleColor = titleColor,
        subtitle = subtitle,
        subtitleColor = rowSubtitleColor(false),
        leadingIcon = leadingIcon?.let { icon ->
            {
                CompositionLocalProvider(LocalContentColor provides titleColor) { icon() }
            }
        },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = !readOnly,
                // 只读时按正常配色显示，避免整行/开关出现「变灰不可用」的误导性透明
                colors = SwitchDefaults.colors(
                    disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurface,
                    disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledUncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    disabledUncheckedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledCheckedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    disabledCheckedTrackColor = MaterialTheme.colorScheme.primary,
                    disabledCheckedBorderColor = MaterialTheme.colorScheme.primary,
                    disabledCheckedIconColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        onClick = null,
        containerColor = if (emphasized) MaterialTheme.colorScheme.surface else Color.Transparent,
        modifier = modifier,
    )
}
