package com.pumppan.controlx2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * 所有设置行的统一骨架。
 *
 * 不用 Material3 的 ListItem：它的 leadingContent 在多行内容时贴顶对齐，
 * 导致 3~4 行的行（Control-IQ、常驻通知、睡眠计划）图标明显靠上。
 * 这里用 Row + CenterVertically，图标永远在整行垂直居中。
 */
@Composable
internal fun BaseRow(
    title: String,
    titleStyle: TextStyle,
    titleColor: Color,
    subtitle: String? = null,
    subtitleColor: Color = Color.Unspecified,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    containerColor: Color = Color.Transparent,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
            .heightIn(min = 56.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(text = title, style = titleStyle, color = titleColor)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (subtitleColor == Color.Unspecified) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        subtitleColor
                    },
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}
