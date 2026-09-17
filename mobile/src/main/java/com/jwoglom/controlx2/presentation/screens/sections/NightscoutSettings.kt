@file:OptIn(ExperimentalMaterial3Api::class)

package com.jwoglom.controlx2.presentation.screens.sections

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.jwoglom.controlx2.sync.nightscout.NightscoutSyncConfig
import com.jwoglom.controlx2.sync.nightscout.NightscoutSyncStatusStore
import com.jwoglom.controlx2.sync.nightscout.ProcessorType
import com.jwoglom.controlx2.sync.nightscout.NightscoutSyncWorker
import com.jwoglom.controlx2.sync.nightscout.normalizeNightscoutUrl
import com.jwoglom.controlx2.presentation.components.HeaderLine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NightscoutSettings(
    innerPadding: PaddingValues = PaddingValues(),
    navController: NavHostController? = null,
    pumpSid: Int = 0
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("controlx2", android.content.Context.MODE_PRIVATE)
    val coroutineScope = rememberCoroutineScope()

    var config by remember { mutableStateOf(NightscoutSyncConfig.load(prefs)) }
    var syncStatus by remember { mutableStateOf(NightscoutSyncStatusStore.load(prefs)) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showApiSecretDialog by remember { mutableStateOf(false) }
    var showProcessorsDialog by remember { mutableStateOf(false) }
    var showLookbackDialog by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = innerPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 0.dp),
        content = {
            item {
                HeaderLine("Nightscout 设置")
                Divider()
            }

            // Enable/Disable Nightscout sync
            item {
                ListItem(
                    headlineContent = {
                        Text(if (config.enabled) "禁用 Nightscout 同步" else "启用 Nightscout 同步")
                    },
                    supportingContent = {
                        Text(
                            if (config.enabled) {
                                "停止自动上传到 Nightscout"
                            } else {
                                "开始自动上传胰岛素泵数据到 Nightscout"
                            }
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (config.enabled) Icons.Filled.Close else Icons.Filled.Check,
                            contentDescription = if (config.enabled) "禁用" else "启用"
                        )
                    },
                    modifier = Modifier.clickable {
                        val newConfig = config.copy(enabled = !config.enabled)
                        config = newConfig
                        NightscoutSyncConfig.save(prefs, newConfig)

                        // Start/stop worker
                        if (newConfig.enabled) {
                            NightscoutSyncWorker.startIfEnabled(context, prefs, pumpSid)
                            Toast.makeText(context, "Nightscout 同步已启用", Toast.LENGTH_SHORT).show()
                        } else {
                            NightscoutSyncWorker.stopIfRunning()
                            Toast.makeText(context, "Nightscout 同步已禁用", Toast.LENGTH_SHORT).show()
                        }

                        syncStatus = NightscoutSyncStatusStore.load(prefs)
                    }
                )
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text("上次成功同步") },
                    supportingContent = {
                        Text(
                            syncStatus.lastSuccessfulSyncMillis?.let { formatTimestamp(it) } ?: "从未"
                        )
                    }
                )
                Divider()
            }

            item {
                val connectionMessage = if (!config.enabled) {
                    "Nightscout 同步已禁用"
                } else if (!config.isValid()) {
                    "Nightscout 配置不完整"
                } else if (!syncStatus.lastError.isNullOrBlank()) {
                    syncStatus.lastError ?: "连接错误"
                } else {
                    "已连接"
                }

                val supportingMessage = if (config.enabled && !syncStatus.lastError.isNullOrBlank()) {
                    syncStatus.lastErrorMillis?.let { "上次失败：${formatTimestamp(it)}" } ?: ""
                } else {
                    ""
                }

                ListItem(
                    headlineContent = { Text("连接状态") },
                    supportingContent = {
                        Column {
                            Text(connectionMessage)
                            if (supportingMessage.isNotBlank()) {
                                Text(supportingMessage)
                            }
                        }
                    }
                )
                Divider()
            }

            // Nightscout URL
            item {
                ListItem(
                    headlineContent = { Text("Nightscout URL") },
                    supportingContent = {
                        Text(config.nightscoutUrl.ifBlank { "未配置" })
                    },
                    modifier = Modifier.clickable {
                        showUrlDialog = true
                    }
                )
                Divider()
            }

            // API Secret
            item {
                ListItem(
                    headlineContent = { Text("API Secret") },
                    supportingContent = {
                        Text(if (config.apiSecret.isNotBlank()) "••••••••" else "未配置")
                    },
                    modifier = Modifier.clickable {
                        showApiSecretDialog = true
                    }
                )
                Divider()
            }

            // Enabled Processors
            item {
                ListItem(
                    headlineContent = { Text("数据类型") },
                    supportingContent = {
                        Text("已启用 ${config.enabledProcessors.size}/${ProcessorType.all().size}")
                    },
                    modifier = Modifier.clickable {
                        showProcessorsDialog = true
                    }
                )
                Divider()
            }

            // Sync Interval
            item {
                ListItem(
                    headlineContent = { Text("同步间隔") },
                    supportingContent = { Text("${config.syncIntervalMinutes} 分钟") },
                    modifier = Modifier.clickable {
                        showIntervalDialog = true
                    }
                )
                Divider()
            }

            // Lookback Period
            item {
                ListItem(
                    headlineContent = { Text("初始回溯") },
                    supportingContent = { Text("首次同步时回溯 ${config.initialLookbackHours} 小时") },
                    modifier = Modifier.clickable {
                        showLookbackDialog = true
                    }
                )
                Divider()
            }

            // Sync Now button
            item {
                ListItem(
                    headlineContent = { Text("立即同步") },
                    supportingContent = { Text("立即触发同步") },
                    leadingContent = {
                        Icon(Icons.Filled.Refresh, contentDescription = "同步")
                    },
                    modifier = Modifier.clickable {
                        if (config.enabled && config.isValid()) {
                            coroutineScope.launch {
                                NightscoutSyncWorker.getInstance(context, prefs, pumpSid).syncNow()
                                syncStatus = NightscoutSyncStatusStore.load(prefs)
                                Toast.makeText(context, "已触发同步", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(
                                context,
                                "请先启用并配置 Nightscout",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
                Divider()
            }
        }
    )

    // URL Dialog
    if (showUrlDialog) {
        var urlInput by remember { mutableStateOf(config.nightscoutUrl) }
        var urlError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Nightscout URL") },
            text = {
                Column {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = {
                            urlInput = it
                            urlError = null
                        },
                        label = { Text("URL") },
                        placeholder = { Text("https://your-nightscout.herokuapp.com") },
                        isError = urlError != null,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (urlError != null) {
                        Text(
                            text = urlError!!,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val normalizedUrl = normalizeNightscoutUrl(urlInput)
                    if (normalizedUrl == null) {
                        urlError = "请输入有效的 Nightscout URL"
                        return@TextButton
                    }

                    val newConfig = config.copy(nightscoutUrl = normalizedUrl)
                    config = newConfig
                    NightscoutSyncConfig.save(prefs, newConfig)
                    showUrlDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // API Secret Dialog
    if (showApiSecretDialog) {
        var secretInput by remember { mutableStateOf(config.apiSecret) }
        AlertDialog(
            onDismissRequest = { showApiSecretDialog = false },
            title = { Text("API Secret") },
            text = {
                OutlinedTextField(
                    value = secretInput,
                    onValueChange = { secretInput = it },
                    label = { Text("API Secret") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newConfig = config.copy(apiSecret = secretInput.trim())
                    config = newConfig
                    NightscoutSyncConfig.save(prefs, newConfig)
                    showApiSecretDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiSecretDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Processors Dialog
    if (showProcessorsDialog) {
        var selectedProcessors by remember { mutableStateOf(config.enabledProcessors) }
        AlertDialog(
            onDismissRequest = { showProcessorsDialog = false },
            title = { Text("选择数据类型") },
            text = {
                LazyColumn {
                    items(ProcessorType.all().toList()) { processorType ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedProcessors = if (selectedProcessors.contains(processorType)) {
                                        selectedProcessors - processorType
                                    } else {
                                        selectedProcessors + processorType
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedProcessors.contains(processorType),
                                onCheckedChange = null
                            )
                            Text(
                                text = processorType.displayName,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newConfig = config.copy(enabledProcessors = selectedProcessors)
                    config = newConfig
                    NightscoutSyncConfig.save(prefs, newConfig)
                    showProcessorsDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showProcessorsDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Sync Interval Dialog
    if (showIntervalDialog) {
        var intervalInput by remember { mutableStateOf(config.syncIntervalMinutes.toString()) }
        AlertDialog(
            onDismissRequest = { showIntervalDialog = false },
            title = { Text("同步间隔") },
            text = {
                OutlinedTextField(
                    value = intervalInput,
                    onValueChange = { intervalInput = it },
                    label = { Text("分钟") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = intervalInput.toIntOrNull() ?: 15
                    val newConfig = config.copy(syncIntervalMinutes = minutes.coerceIn(5, 1440))
                    config = newConfig
                    NightscoutSyncConfig.save(prefs, newConfig)

                    // Restart worker with new interval
                    if (config.enabled) {
                        NightscoutSyncWorker.stopIfRunning()
                        NightscoutSyncWorker.startIfEnabled(context, prefs, pumpSid)
                    }

                    showIntervalDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showIntervalDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Lookback Dialog
    if (showLookbackDialog) {
        var lookbackInput by remember { mutableStateOf(config.initialLookbackHours.toString()) }
        AlertDialog(
            onDismissRequest = { showLookbackDialog = false },
            title = { Text("初始回溯周期") },
            text = {
                Column {
                    Text("首次启用时同步回溯时长（小时）")
                    OutlinedTextField(
                        value = lookbackInput,
                        onValueChange = { lookbackInput = it },
                        label = { Text("小时") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val hours = lookbackInput.toIntOrNull() ?: 24
                    val newConfig = config.copy(initialLookbackHours = hours.coerceIn(1, 720))
                    config = newConfig
                    NightscoutSyncConfig.save(prefs, newConfig)
                    showLookbackDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLookbackDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

private fun formatTimestamp(timestampMillis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    return Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}
