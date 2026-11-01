package com.pumppan.controlx2.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp

/**
 * 设置组容器。
 *
 * - readOnly=true（X2）：就地展开只读子项，零跳转
 * - readOnly=false（Mobi）：点击父行导航到子页面
 *
 * @param title 组标题
 * @param summary 收起时父行显示的当前值摘要
 * @param readOnly 是否只读（决定展开 vs 导航）
 * @param children 展开时显示的只读子项（X2 路径）
 * @param onNavigate 点击父行导航回调（Mobi 路径）
 */
@Composable
fun SettingsGroup(
    title: String,
    summary: String? = null,
    readOnly: Boolean = false,
    children: @Composable () -> Unit = {},
    onNavigate: () -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column {
        ListItem(
            headlineContent = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            supportingContent = summary?.let { text ->
                {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingContent = {
                if (readOnly) {
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.rotate(if (expanded) 180f else 0f),
                    )
                }
            },
            modifier = Modifier
                .heightIn(min = 56.dp)
                .clickable {
                    if (readOnly) expanded = !expanded else onNavigate()
                },
        )

        if (readOnly) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    children()
                }
            }
        }
    }
}
