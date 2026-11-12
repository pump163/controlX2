@file:OptIn(ExperimentalMaterial3Api::class)

package com.jwoglom.controlx2.presentation.screens.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.presentation.components.HeaderLine
import com.jwoglom.controlx2.shared.diag.DiagMonitor
import com.pumppan.controlx2.PumppanPrefs
import com.pumppan.controlx2.PumppanPumpModelRow
import com.pumppan.controlx2.ui.ActionRow

/**
 * Debug 页二级页「危险指令」：承载所有"打开就有副作用、随时会暂停"的高风险开关。
 *
 * 现有条目：
 * 1. 泵型号覆盖（[PumppanPumpModelRow]）
 * 2. 允许不安全的原始指令（`unsafe-raw-commands`）
 * 3. 断连监控（需要才开）：整块平铺，不折叠
 *
 * 断连监控为蓝牙断连专项加的取证手段，属于"打开就持续写盘"的开关。
 * 停用方式（按用户约定）：**注释掉入口 + 开关默认关**，不整体删除代码，恢复时取消注释即可。
 * 本页只负责呈现与导航，pref key、默认值与读写逻辑均未改动。
 */
@Composable
fun DangerousCommands(
    innerPadding: PaddingValues = PaddingValues(),
    navigateBack: () -> Unit,
    navigateToDiag: () -> Unit = {},
    navigateToScheduledBolus: () -> Unit = {},
) {
    val context = LocalContext.current
    var unsafeRawCommands by remember {
        mutableStateOf(Prefs(context).unsafeRawCommandsEnabled())
    }
    var diagEnabled by remember {
        mutableStateOf(DiagMonitor.snapshot().enabled)
    }

    LazyColumn(
        contentPadding = innerPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 0.dp),
        content = {
            item {
                HeaderLine(stringResource(R.string.settings_dangerous_commands))
                Divider()
            }

            // 泵型号覆盖：沿用原有选择对话框与持久化逻辑。
            item {
                PumppanPumpModelRow()
                Divider()
            }

            // 允许不安全的原始指令：沿用原有 pref 与开关联动。
            item {
                ActionRow(
                    title = context.getString(R.string.settings_allow_unsafe_raw_commands),
                    subtitle = context.getString(R.string.settings_allow_unsafe_raw_commands_subtitle),
                    leadingIcon = {
                        Icon(
                            if (unsafeRawCommands) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = context.getString(R.string.settings_unsafe_raw_commands_icon),
                        )
                    },
                    onClick = {
                        unsafeRawCommands = !unsafeRawCommands
                        Prefs(context).setUnsafeRawCommandsEnabled(unsafeRawCommands)
                    },
                )
                Divider()
            }

            // ---- 断连监控（需要才开）：与上一条同样式，左侧图标表示开关状态 ----
            item {
                ActionRow(
                    title = context.getString(R.string.settings_diag_monitor_title),
                    subtitle = context.getString(R.string.settings_diag_monitor_sub),
                    leadingIcon = {
                        Icon(
                            if (diagEnabled) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = context.getString(R.string.settings_diag_monitor_title),
                        )
                    },
                    onClick = {
                        diagEnabled = !diagEnabled
                        DiagMonitor.setEnabled(context, diagEnabled)
                    },
                )
            }

            // 风险提示与监控页入口跟在开关行下方，不再占一个右侧开关位。
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        text = context.getString(R.string.settings_diag_warning),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = navigateToDiag,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(context.getString(R.string.settings_diag_open))
                    }
                }
                Divider()
            }

            // 定时大剂量「不确认即输注」（2026-11-12 用户口径）：只在本组暴露，持久化；
            // 打开时弹一次二次确认，之后不做定期复验（高手高频使用，新手不会碰）。
            item {
                var autoSend by remember { mutableStateOf(PumppanPrefs.scheduledAutoSend(context)) }
                var confirmEnable by remember { mutableStateOf(false) }
                ActionRow(
                    title = context.getString(R.string.settings_scheduled_autosend),
                    subtitle = context.getString(R.string.settings_scheduled_autosend_sub),
                    leadingIcon = {
                        Icon(
                            if (autoSend) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        if (autoSend) {
                            autoSend = false
                            PumppanPrefs.setScheduledAutoSend(context, false)
                        } else {
                            confirmEnable = true
                        }
                    },
                )
                if (confirmEnable) {
                    AlertDialog(
                        onDismissRequest = { confirmEnable = false },
                        title = { Text(context.getString(R.string.settings_scheduled_autosend)) },
                        text = { Text(context.getString(R.string.settings_scheduled_autosend_warn)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmEnable = false
                                autoSend = true
                                PumppanPrefs.setScheduledAutoSend(context, true)
                            }) { Text(context.getString(R.string.scheduled_continue)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmEnable = false }) {
                                Text(context.getString(R.string.scheduled_abort))
                            }
                        },
                    )
                }
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.app_back)) },
                    leadingContent = { Icon(Icons.Filled.ArrowBack, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = navigateBack),
                )
            }
        },
    )
}
