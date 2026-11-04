package com.pumppan.controlx2.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 导航行：标题 + 可选副标题 + 尾部图标，点击进入子页面。
 *
 * @param title 行标题
 * @param onClick 点击回调；readOnly=true 时不调用
 * @param subtitle 可选副标题（显示当前值摘要）
 * @param leadingIcon 可选前置图标
 * @param trailingIcon 尾部图标，null 时不显示
 * @param readOnly 只读：不可点击、文字与图标降透明度
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
    val titleColor = rowTitleColor(readOnly)

    BaseRow(
        title = title,
        titleStyle = MaterialTheme.typography.bodyLarge,
        titleColor = titleColor,
        subtitle = subtitle,
        // 副标题是「显示值」，只读时也保持正常色，方便 X2 上查看状态
            subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
        leadingIcon = leadingIcon?.let { icon ->
            { CompositionLocalProvider(LocalContentColor provides titleColor) { icon() } }
        },
        trailing = trailingIcon?.let { icon ->
            { Icon(imageVector = icon, contentDescription = null, tint = titleColor) }
        },
        onClick = if (readOnly) null else onClick,
        modifier = modifier,
    )
}
