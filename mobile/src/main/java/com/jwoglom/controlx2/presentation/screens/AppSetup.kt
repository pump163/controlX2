@file:OptIn(ExperimentalMaterial3Api::class)

package com.jwoglom.controlx2.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.presentation.components.DialogScreen
import com.jwoglom.controlx2.presentation.components.Line
import com.jwoglom.controlx2.presentation.components.ServiceDisabledMessage
import com.jwoglom.controlx2.presentation.navigation.Screen
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme
import com.jwoglom.controlx2.shared.MessagePaths
import com.jwoglom.controlx2.shared.enums.GlucoseUnit
import com.jwoglom.controlx2.shared.util.twoDecimalPlaces
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun AppSetup(
    navController: NavHostController? = null,
    sendMessage: (String, ByteArray) -> Unit,
    preview: Boolean = false,
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val ds = LocalDataStore.current

    var connectionSharingEnabled by remember { mutableStateOf(Prefs(context).connectionSharingEnabled()) }
    var insulinDeliveryActions by remember { mutableStateOf(Prefs(context).insulinDeliveryActions()) }
    var bolusConfirmationInsulinThreshold by remember { mutableStateOf(Prefs(context).bolusConfirmationInsulinThreshold()) }
    var wearAutoApproveTimeout by remember { mutableStateOf(Prefs(context).wearBolusAutoApproveTimeoutSeconds()) }
    var checkForUpdates by remember { mutableStateOf(Prefs(context).checkForUpdates()) }
    var autoFetchHistoryLogs by remember { mutableStateOf(Prefs(context).autoFetchHistoryLogs()) }
    var glucoseUnit by remember { mutableStateOf(Prefs(context).glucoseUnit()) }

    var showGlucoseUnitDialog by remember { mutableStateOf(false) }
    var showInsulinWarningDialog by remember { mutableStateOf(false) }
    var showBolusThresholdDialog by remember { mutableStateOf(false) }
    var showWearAutoApproveDialog by remember { mutableStateOf(false) }
    var showUpdatesWarningDialog by remember { mutableStateOf(false) }

    DialogScreen(
        "应用设置",
        buttonContent = {
            Button(
                onClick = {
                    if (navController?.popBackStack() == false) {
                        navController.navigate(Screen.PumpSetup.route)
                    }
                    Prefs(context).setAppSetupComplete(false)
                }
            ) {
                Text("返回")
            }
            Button(
                onClick = {
                    Prefs(context).setAppSetupComplete(true)
                    sendMessage(MessagePaths.TO_SERVER_REFRESH_HISTORY_LOG_SYNC, "".toByteArray())
                    // Do not restart the app at the end of setup; restarting here can race pump
                    // bootstrap and leave CommService in a null-peripheral startup loop.
                    sendMessage(MessagePaths.TO_SERVER_REQUEST_SERVICE_STATUS, "".toByteArray())
                    navController?.navigate(Screen.Landing.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            ) {
                Text("继续")
            }
        }
    ) {
        item {
            ServiceDisabledMessage(sendMessage = sendMessage)
        }
//        item {
//            ListItem(
//                headlineContent = {
//                    Text("Connection Sharing")
//                },
//                supportingContent = {
//                    Text("Enable t:connect app connection sharing. Enables workarounds to run ControlX2 and the t:connect app at the same time.")
//                },
//                trailingContent = {
//                    Switch(
//                        checked = connectionSharingEnabled,
//                        onCheckedChange = {
//                            connectionSharingEnabled = it
//                            Prefs(context).setConnectionSharingEnabled(it)
//                            coroutineScope.launch {
//                                delay(250)
//                                sendMessage(
//                                    MessagePaths.TO_SERVER_APP_RELOAD,
//                                    "".toByteArray()
//                                )
//                            }
//                        }
//                    )
//                },
//                modifier = Modifier.clickable {
//                    connectionSharingEnabled = !connectionSharingEnabled
//                    Prefs(context).setConnectionSharingEnabled(connectionSharingEnabled)
//                    coroutineScope.launch {
//                        delay(250)
//                        sendMessage(
//                            MessagePaths.TO_SERVER_APP_RELOAD,
//                            "".toByteArray()
//                        )
//                    }
//                }
//            )
//            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
//        }
        item {
            ListItem(
                headlineContent = {
                    Text("胰岛素输注控制")
                },
                supportingContent = {
                    Text("允许通过手机或手表远程输注大剂量")
                },
                trailingContent = {
                    Switch(
                        checked = insulinDeliveryActions,
                        onCheckedChange = {
                            if (!insulinDeliveryActions) {
                                showInsulinWarningDialog = true
                            } else {
                                insulinDeliveryActions = false
                                Prefs(context).setInsulinDeliveryActions(false)
                                coroutineScope.launch {
                                    delay(250)
                                    sendMessage(
                                        MessagePaths.TO_SERVER_APP_RELOAD,
                                        "".toByteArray()
                                    )
                                }
                            }
                        }
                    )
                },
                modifier = Modifier.clickable {
                    if (!insulinDeliveryActions) {
                        showInsulinWarningDialog = true
                    } else {
                        insulinDeliveryActions = false
                        Prefs(context).setInsulinDeliveryActions(false)
                        coroutineScope.launch {
                            delay(250)
                            sendMessage(
                                MessagePaths.TO_SERVER_APP_RELOAD,
                                "".toByteArray()
                            )
                        }
                    }
                }
            )
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
        }
        item {
            if (insulinDeliveryActions || preview) {
                ListItem(
                    headlineContent = {
                        Text("大剂量确认阈值")
                    },
                    supportingContent = {
                        Text(
                            bolusConfirmationInsulinThreshold.let {
                                if (it == 0.0) "所有大剂量都需要确认"
                                else "超过 ${twoDecimalPlaces(it)}u 的大剂量需要确认"
                            }
                        )
                    },
                    trailingContent = {
                        Text(
                            text = bolusConfirmationInsulinThreshold.let { if (it == 0.0) "总是" else "${twoDecimalPlaces(it)}u" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.clickable {
                        showBolusThresholdDialog = true
                    }
                )
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
            }
        }
        item {
            if (insulinDeliveryActions || preview) {
                ListItem(
                    headlineContent = {
                        Text("手表超时自动批准")
                    },
                    supportingContent = {
                        Text(
                            when (wearAutoApproveTimeout) {
                                0 -> "永不自动批准（需要手动确认）"
                                else -> "如果未取消，${wearAutoApproveTimeout} 秒后自动批准"
                            }
                        )
                    },
                    trailingContent = {
                        Text(
                            text = when (wearAutoApproveTimeout) {
                                0 -> "永不"
                                60 -> "1 分钟"
                                300 -> "5 分钟"
                                else -> "${wearAutoApproveTimeout}s"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.clickable {
                        showWearAutoApproveDialog = true
                    }
                )
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
            }
        }
        item {
            ListItem(
                headlineContent = {
                    Text("检查更新")
                },
                supportingContent = {
                    Text("自动检查 ControlX2 更新")
                },
                trailingContent = {
                    Switch(
                        checked = checkForUpdates,
                        onCheckedChange = {
                            if (checkForUpdates) {
                                showUpdatesWarningDialog = true
                            } else {
                                checkForUpdates = true
                                Prefs(context).setCheckForUpdates(true)
                            }
                        }
                    )
                },
                modifier = Modifier.clickable {
                    if (checkForUpdates) {
                        showUpdatesWarningDialog = true
                    } else {
                        checkForUpdates = true
                        Prefs(context).setCheckForUpdates(true)
                    }
                }
            )
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
        }
        item {
            ListItem(
                headlineContent = {
                    Text("自动获取历史记录")
                },
                supportingContent = {
                    Text("开启后才能在首页绘制 CGM 图形。")
                },
                trailingContent = {
                    Switch(
                        checked = autoFetchHistoryLogs,
                        onCheckedChange = {
                            autoFetchHistoryLogs = it
                            Prefs(context).setAutoFetchHistoryLogs(it)
                        }
                    )
                },
                modifier = Modifier.clickable {
                    autoFetchHistoryLogs = !autoFetchHistoryLogs
                    Prefs(context).setAutoFetchHistoryLogs(autoFetchHistoryLogs)
                }
            )
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
        }
        item {
            ListItem(
                headlineContent = {
                    Text("血糖单位")
                },
                supportingContent = {
                    Text("血糖读数单位选择 mg/dL 或 mmol/L")
                },
                trailingContent = {
                    Text(
                        text = glucoseUnit?.abbreviation ?: "未设置",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                modifier = Modifier.clickable {
                    showGlucoseUnitDialog = true
                }
            )
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
        }
    }

    // Glucose Unit Selection Dialog
    if (showGlucoseUnitDialog) {
        AlertDialog(
            onDismissRequest = { showGlucoseUnitDialog = false },
            title = { Text("选择血糖单位") },
            text = {
                Column {
                    GlucoseUnit.values().forEach { unit ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    glucoseUnit = unit
                                    Prefs(context).setGlucoseUnit(unit)
                                    ds.glucoseUnitPreference.value = unit
                                    showGlucoseUnitDialog = false
                                    // Glucose unit is read live by the running pump
                                    // session; only sync to wear, no reload needed.
                                    coroutineScope.launch {
                                        sendMessage(MessagePaths.TO_CLIENT_GLUCOSE_UNIT, unit.name.toByteArray())
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = glucoseUnit == unit,
                                onClick = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(text = unit.displayName)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGlucoseUnitDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Insulin Delivery Warning Dialog
    if (showInsulinWarningDialog) {
        AlertDialog(
            onDismissRequest = { showInsulinWarningDialog = false },
            title = { Text("警告") },
            text = {
                Text("警告：本软件为非官方实验性软件。启用胰岛素输注将允许您的手机或手表向胰岛素泵远程发送大剂量。启用此设置前，请充分了解其安全与安全隐患。为安全起见，请在胰岛素泵上确认大剂量操作。发送大剂量命令时，胰岛素泵会发出提示音。")
            },
            confirmButton = {
                Button(onClick = {
                    insulinDeliveryActions = true
                    Prefs(context).setInsulinDeliveryActions(true)
                    showInsulinWarningDialog = false
                    // Soft-apply: flip the pumpx2 static on the running pump
                    // session instead of restarting the process. This avoids
                    // killing the BLE link mid-handshake right after pairing.
                    coroutineScope.launch {
                        delay(250)
                        sendMessage(MessagePaths.TO_SERVER_APPLY_RUNTIME_PREFS, "".toByteArray())
                    }
                }) {
                    Text("启用")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInsulinWarningDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Updates Warning Dialog
    if (showUpdatesWarningDialog) {
        AlertDialog(
            onDismissRequest = { showUpdatesWarningDialog = false },
            title = { Text("禁用更新检查") },
            text = {
                Text("请定期访问 ControlX2 GitHub 页面并订阅发布通知，以确保及时获取更新。警告：禁用此选项后，您将不会收到任何新功能、安全或安全更新的提醒。")
            },
            confirmButton = {
                Button(onClick = {
                    checkForUpdates = false
                    Prefs(context).setCheckForUpdates(false)
                    showUpdatesWarningDialog = false
                }) {
                    Text("禁用")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdatesWarningDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Wear Auto-Approve Timeout Dialog
    if (showWearAutoApproveDialog) {
        val options = listOf(
            0 to "永不（需要手动确认）",
            30 to "30 秒",
            60 to "1 分钟",
            300 to "5 分钟",
        )
        AlertDialog(
            onDismissRequest = { showWearAutoApproveDialog = false },
            title = { Text("手表超时自动批准") },
            text = {
                Column {
                    Text(
                        "手表请求大剂量后，如未在手机上取消，将在此超时时间后自动批准。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    options.forEach { (seconds, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    wearAutoApproveTimeout = seconds
                                    Prefs(context).setWearBolusAutoApproveTimeoutSeconds(seconds)
                                    showWearAutoApproveDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = wearAutoApproveTimeout == seconds,
                                onClick = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(text = label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWearAutoApproveDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Bolus Threshold Dialog
    if (showBolusThresholdDialog) {
        var thresholdInput by remember { mutableStateOf(bolusConfirmationInsulinThreshold.let { if (it == 0.0) "" else "$it" }) }
        AlertDialog(
            onDismissRequest = { showBolusThresholdDialog = false },
            title = { Text("大剂量确认阈值") },
            text = {
                OutlinedTextField(
                    value = thresholdInput,
                    onValueChange = { thresholdInput = it },
                    label = { Text("阈值（单位）") },
                    supportingText = { Text("输入 0 表示始终需要确认") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newValue = thresholdInput.toDoubleOrNull() ?: 0.0
                    bolusConfirmationInsulinThreshold = newValue
                    Prefs(context).setBolusConfirmationInsulinThreshold(newValue)
                    showBolusThresholdDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBolusThresholdDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}


@Preview(showBackground = true)
@Composable
internal fun AppSetupDefaultPreview() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            AppSetup(
                sendMessage = {_, _ -> },
                preview = true,
            )
        }
    }
}
