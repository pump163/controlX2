@file:OptIn(ExperimentalMaterial3Api::class)

package com.jwoglom.controlx2.presentation.screens.sections

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.shared.MessagePaths
import com.jwoglom.controlx2.presentation.components.HeaderLine
import com.jwoglom.controlx2.sync.xdrip.XdripBroadcastSender
import com.jwoglom.controlx2.sync.xdrip.XdripPayloadGroup
import com.jwoglom.controlx2.sync.xdrip.XdripSyncConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

@Composable
fun XdripSettings(
    innerPadding: PaddingValues = PaddingValues(),
    navController: NavHostController? = null,
    sendMessage: (String, ByteArray) -> Unit,
    navigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = Prefs(context).prefs()
    val coroutineScope = rememberCoroutineScope()

    var config by remember { mutableStateOf(XdripSyncConfig.load(prefs)) }

    fun saveConfig(newConfig: XdripSyncConfig, showToastText: String? = null) {
        val oldConfig = config
        config = newConfig
        XdripSyncConfig.save(prefs, newConfig)

        if (newConfig.requiresReloadComparedTo(oldConfig)) {
            coroutineScope.launch {
                delay(250)
                sendMessage(MessagePaths.TO_SERVER_FORCE_RELOAD, "".toByteArray())
                delay(250)
                sendMessage(MessagePaths.TO_SERVER_APP_RELOAD, "".toByteArray())
            }
        }

        if (showToastText != null) {
            Toast.makeText(context, showToastText, Toast.LENGTH_SHORT).show()
        }
    }

    fun togglePayload(payloadGroup: XdripPayloadGroup) {
        val payloadEnabled = config.isPayloadEnabled(payloadGroup)
        saveConfig(config.withPayloadEnabled(payloadGroup, !payloadEnabled))
    }

    LazyColumn(
        contentPadding = innerPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 0.dp),
        content = {
            item {
                HeaderLine("xDrip 设置")
                Divider()
            }

            item {
                ListItem(
                    headlineContent = {
                        Text(if (config.enabled) "禁用 xDrip 同步" else "启用 xDrip 同步")
                    },
                    supportingContent = {
                        Text(
                            if (config.enabled) {
                                "停止发送胰岛素泵和 CGM 更新到 xDrip"
                            } else {
                                "启用 xDrip 广播以发送所选数据组"
                            }
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (config.enabled) Icons.Filled.Close else Icons.Filled.Check,
                            contentDescription = if (config.enabled) "禁用" else "启用"
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { saveConfig(config.copy(enabled = it)) }
                        )
                    },
                    modifier = Modifier.clickable {
                        val newEnabled = !config.enabled
                        saveConfig(
                            config.copy(enabled = newEnabled),
                            if (newEnabled) "xDrip 同步已启用" else "xDrip 同步已禁用"
                        )
                    }
                )
                Divider()
            }

            item {
                XdripPayloadToggleItem(
                    title = "发送 SGV",
                    subtitle = "广播当前血糖读数到 xDrip",
                    enabled = config.sendCgmSgv,
                    onToggle = { togglePayload(XdripPayloadGroup.CGM) }
                )
                Divider()
            }

            item {
                XdripPayloadToggleItem(
                    title = "发送设备状态",
                    subtitle = "广播电池、活性胰岛素（IOB）、储药器和基础率状态",
                    enabled = config.sendPumpDeviceStatus,
                    onToggle = { togglePayload(XdripPayloadGroup.PUMP_DEVICE_STATUS) }
                )
                Divider()
            }

            item {
                XdripPayloadToggleItem(
                    title = "发送治疗记录",
                    subtitle = "广播大剂量治疗记录到 xDrip",
                    enabled = config.sendTreatments,
                    onToggle = { togglePayload(XdripPayloadGroup.TREATMENTS) }
                )
                Divider()
            }

            item {
                XdripPayloadToggleItem(
                    title = "发送状态栏",
                    subtitle = "广播单行胰岛素泵状态文本",
                    enabled = config.sendStatusLine,
                    onToggle = { togglePayload(XdripPayloadGroup.STATUS_LINE) }
                )
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text("发送诊断测试数据") },
                    supportingContent = {
                        Text("发送一次性测试 SGV 和状态栏广播 intent")
                    },
                    leadingContent = {
                        Icon(
                            Icons.Filled.BugReport,
                            contentDescription = "诊断图标"
                        )
                    },
                    modifier = Modifier.clickable {
                        sendDiagnosticsPayload(context)
                    }
                )
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text("返回") },
                    leadingContent = { Icon(Icons.Filled.ArrowBack, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = navigateBack),
                )
            }
        }
    )
}

@Composable
private fun XdripPayloadToggleItem(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() }
            )
        },
        modifier = Modifier.clickable { onToggle() }
    )
}

private fun sendDiagnosticsPayload(context: Context) {
    val now = Instant.now()
    val sender = XdripBroadcastSender(context)
    val sgvSent = sender.sendSgv(
        JSONArray().put(
            JSONObject().apply {
                put("mgdl", 123)
                put("mills", now.toEpochMilli())
                put("direction", "Flat")
            }
        ).toString()
    )
    val statusSent = sender.sendExternalStatusline("ControlX2 test statusline @ ${now}")

    val message = buildString {
        append("诊断已发送")
        append(if (sgvSent) "（SGV 正常" else "（SGV 跳过")
        append(if (statusSent) "，状态栏正常）" else "，状态栏跳过）")
    }
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
