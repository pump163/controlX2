package com.pumppan.controlx2.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 导航行：标题 + 可选副标题 + 尾部图标，点击进入子页面。
 *
 * @param title 行标题
 * @param onClick 点击回调；readOnly=true 时不调用
 * @param subtitle 可选副标题（显示当前值摘要）
 * @param leadingIcon 可选前置图标
 * @param trailingIcon 尾部图标，null 时不显示
 * @param readOnly 只读：不可点击、文字降透明度
 */
@Composable
fun NavigationRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: ImageVector? = null,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                color = rowTitleColor(readOnly),
                style = MaterialTheme.typography.bodyLarge,
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
        trailingContent = trailingIcon?.let { icon ->
            { Icon(imageVector = icon, contentDescription = null) }
        },
        modifier = modifier
            .heightIn(min = 56.dp)
            .clickable(enabled = !readOnly, onClick = onClick),
    )
}
