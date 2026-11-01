package com.pumppan.controlx2.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 * @param readOnly 只读：不可点击、文字降透明度（X2 上只能看）
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
        modifier = modifier
            .heightIn(min = 56.dp)
            .clickable(enabled = !readOnly, onClick = onClick),
    )
}
