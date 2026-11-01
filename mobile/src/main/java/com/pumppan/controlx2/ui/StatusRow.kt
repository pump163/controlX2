package com.pumppan.controlx2.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 只读状态行：标题 + 当前值。用于 X2 上能读不能写的设置项。
 * 数值照常显示，不因不可操作而变灰。
 *
 * @param title 行标题
 * @param status 当前状态值文本，null 时只显示标题
 * @param leadingIcon 可选前置图标
 */
@Composable
fun StatusRow(
    title: String,
    status: String?,
    leadingIcon: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = status?.let { value ->
            {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingContent = leadingIcon,
        modifier = modifier.heightIn(min = 56.dp),
    )
}
