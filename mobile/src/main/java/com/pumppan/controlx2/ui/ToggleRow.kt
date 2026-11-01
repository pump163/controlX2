package com.pumppan.controlx2.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
    ListItem(
        headlineContent = {
            Text(
                text = title,
                color = rowTitleColor(readOnly),
                style = rowTitleStyle(emphasized),
            )
        },
        supportingContent = subtitle?.let { text ->
            {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = rowSubtitleColor(readOnly),
                )
            }
        },
        leadingContent = leadingIcon,
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = !readOnly,
            )
        },
        modifier = modifier.heightIn(min = 56.dp),
    )
}
