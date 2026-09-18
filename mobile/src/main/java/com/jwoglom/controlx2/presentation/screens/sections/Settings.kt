@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3Api::class)

package com.jwoglom.controlx2.presentation.screens.sections

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DataThresholding
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.presentation.components.HeaderLine
import com.jwoglom.controlx2.presentation.navigation.Screen
import com.jwoglom.controlx2.presentation.screens.sections.components.VersionInfo
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme
import com.jwoglom.controlx2.presentation.util.SupportBundleSummary
import com.jwoglom.controlx2.presentation.util.formatLogLineCount
import com.jwoglom.controlx2.presentation.util.getSupportBundleSummary
import com.jwoglom.controlx2.presentation.util.sendSupportBundleEmail
import com.jwoglom.controlx2.presentation.util.shareSupportBundle
import com.jwoglom.controlx2.shared.FeatureFlag
import com.jwoglom.controlx2.shared.MessagePaths
import com.jwoglom.controlx2.shared.enums.DeviceRole
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.controlx2.util.AppVersionCheck
import com.jwoglom.controlx2.util.AppVersionInfo
import com.jwoglom.controlx2.util.rescueResetThisDevice
import com.jwoglom.controlx2.util.switchDeviceRole
import com.jwoglom.pumpx2.pump.PumpState
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.control.ChangeTimeDateRequest
import com.jwoglom.pumpx2.pump.messages.request.control.PlaySoundRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.time.Instant

@Composable
fun Settings(
    innerPadding: PaddingValues = PaddingValues(),
    navController: NavHostController? = null,
    sendMessage: (String, ByteArray) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    navigateToDebugOptions: () -> Unit = {},
    navigateToNightscoutSettings: () -> Unit = {},
    navigateToXdripSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showPumpSetupConfirmDialog by remember { mutableStateOf(false) }

    var showSyncTimeDialog by remember { mutableStateOf(false) }
    var showPlaySoundDialog by remember { mutableStateOf(false) }
    var showSupportBundleDialog by remember { mutableStateOf(false) }
    var supportBundleSummary by remember { mutableStateOf<SupportBundleSummary?>(null) }
    var showDeviceRoleDialog by remember { mutableStateOf(false) }
    var showRescueDialog by remember { mutableStateOf(false) }
    var currentDeviceRole by remember { mutableStateOf(Prefs(context).deviceRole()) }

    LazyColumn(
        contentPadding = innerPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 0.dp),
        content = {
            item {
                HeaderLine("设置")
                Divider()
            }

            item {
                VersionInfo(context)
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text("发送支持包") },
                    supportingContent = { Text("生成包含 ControlX2 调试日志的 zip 文件。") },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Help,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable {
                        val summary = getSupportBundleSummary(context)
                        if (summary == null) {
                            Toast.makeText(context, "没有可分享的调试日志。", Toast.LENGTH_SHORT).show()
                        } else {
                            supportBundleSummary = summary
                            showSupportBundleDialog = true
                        }
                    }
                )
                Divider()
            }

            item {
                if (!Prefs(context).serviceEnabled()) {
                    ListItem(
                        headlineContent = { Text("启用 ControlX2 服务") },
                        supportingContent = { Text("启动后台服务，并使其在打开应用时自动启动。") },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "启动图标",
                            )
                        },
                        modifier = Modifier.clickable {
                            Prefs(context).setServiceEnabled(true)
                            coroutineScope.launch {
                                delay(250)
                                // reload service, if running
                                sendMessage(MessagePaths.TO_SERVER_FORCE_RELOAD, "".toByteArray())
                                delay(250)
                                // reload main activity as fallback
                                sendMessage(MessagePaths.TO_SERVER_APP_RELOAD, "".toByteArray())
                            }
                        }
                    )
                    Divider()
                }
            }
//            item {
//                if (Prefs(context).connectionSharingEnabled()) {
//                    ListItem(
//                        headlineContent = { Text("Disable connection sharing") },
//                        supportingContent = { Text("Removes workarounds to run WearX2 and the t:connect app at the same time.") },
//                        leadingContent = {
//                            Icon(
//                                Icons.Filled.Close,
//                                contentDescription = "Stop icon",
//                            )
//                        },
//                        modifier = Modifier.clickable {
//                            Prefs(context).setConnectionSharingEnabled(false)
//                            coroutineScope.launch {
//                                withContext(Dispatchers.IO) {
//                                    delay(250)
//                                }
//                                sendMessage(MessagePaths.TO_SERVER_FORCE_RELOAD, "".toByteArray())
//                            }
//                        }
//                    )
//                } else {
//                    ListItem(
//                        headlineContent = { Text("Enable connection sharing") },
//                        supportingContent = { Text("Enables workarounds to run WearX2 and the t:connect app at the same time.") },
//                        leadingContent = {
//                            Icon(
//                                Icons.Filled.Check,
//                                contentDescription = "启动图标",
//                            )
//                        },
//                        modifier = Modifier.clickable {
//                            Prefs(context).setConnectionSharingEnabled(true)
//                            coroutineScope.launch {
//                                withContext(Dispatchers.IO) {
//                                    delay(250)
//                                }
//                                sendMessage(MessagePaths.TO_SERVER_FORCE_RELOAD, "".toByteArray())
//                            }
//                        }
//                    )
//                }
//                Divider()
//            }

            item {
                ListItem(
                    headlineContent = { Text("强制重载服务") },
                    supportingContent = { Text("重启后台服务。") },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "重新加载图标",
                        )
                    },
                    modifier = Modifier.clickable {
                        sendMessage(MessagePaths.TO_SERVER_FORCE_RELOAD, "".toByteArray())
                    }
                )
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text("重新配置胰岛素泵") },
                    supportingContent = { Text("断开并重新配对胰岛素泵。") },
                    leadingContent = {
                        Icon(
                            painterResource(R.drawable.pump),
                            tint = Color.Unspecified,
                            contentDescription = "设置图标",
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    modifier = Modifier.clickable {
                        showPumpSetupConfirmDialog = true
                    }
                )
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text("重新配置应用") },
                    supportingContent = { Text("启用或禁用胰岛素输注操作。") },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "设置图标",
                        )
                    },
                    modifier = Modifier.clickable {
                        Prefs(context).setAppSetupComplete(false)
                        navController?.navigate(Screen.AppSetup.route)
                    }
                )
                Divider()
            }

            item {
                var ffEnabled by remember { mutableStateOf(FeatureFlag.enabled(context,
                    FeatureFlag.BTHostSwitch
                ))}

                if (ffEnabled) {
                    val roleLabel = when (currentDeviceRole) {
                        DeviceRole.PUMP_HOST -> "手机（胰岛素泵主机）"
                        DeviceRole.CLIENT -> "手表（胰岛素泵主机）"
                    }
                    ListItem(
                        headlineContent = { Text("胰岛素泵主机设备") },
                        supportingContent = { Text("当前：$roleLabel。") },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Devices,
                                contentDescription = "设备角色图标",
                            )
                        },
                        modifier = Modifier.clickable {
                            showDeviceRoleDialog = true
                        }
                    )
                    Divider()
                    ListItem(
                        headlineContent = { Text("重置并在此设备上重新开始") },
                        supportingContent = { Text("如果卡住时使用。强制此手机作为胰岛素泵主机并清除胰岛素泵配对。") },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Devices,
                                contentDescription = "重置图标",
                            )
                        },
                        modifier = Modifier.clickable {
                            showRescueDialog = true
                        }
                    )
                    Divider()
                }
            }

            item {
                ListItem(
                    headlineContent = { Text("Nightscout") },
                    supportingContent = { Text("配置 Nightscout 同步以上传胰岛素泵数据。") },
                    leadingContent = {
                        Icon(
                            Icons.Filled.CloudSync,
                            contentDescription = "Nightscout 图标",
                        )
                    },
                    modifier = Modifier.clickable {
                        navigateToNightscoutSettings()
                    }
                )
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text("xDrip") },
                    supportingContent = { Text("配置 xDrip 广播以发送胰岛素泵和 CGM 数据。") },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Sync,
                            contentDescription = "xDrip 图标",
                        )
                    },
                    modifier = Modifier.clickable {
                        navigateToXdripSettings()
                    }
                )
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text("同步胰岛素泵时间") },
                    supportingContent = { Text("将胰岛素泵时钟设置为当前手机时间。") },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Timer,
                            contentDescription = "同步时间图标",
                        )
                    },
                    modifier = Modifier.clickable {
                        showSyncTimeDialog = true
                    }
                )
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text("查找我的胰岛素泵") },
                    supportingContent = { Text("在胰岛素泵上播放声音以帮助定位。") },
                    leadingContent = {
                        Icon(
                            Icons.Filled.NotificationsActive,
                            contentDescription = "播放声音图标",
                        )
                    },
                    modifier = Modifier.clickable {
                        showPlaySoundDialog = true
                    }
                )
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text("调试选项") },
                    supportingContent = { Text("执行调试选项。") },
                    leadingContent = {
                        Icon(
                            Icons.Filled.DeveloperMode,
                            contentDescription = "设置图标",
                        )
                    },
                    modifier = Modifier.clickable {
                        navigateToDebugOptions()
                    }
                )
                Divider()
            }
        }
    )

    if (showPumpSetupConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showPumpSetupConfirmDialog = false },
            title = { Text("忘记胰岛素泵？") },
            text = {
                Text("清除此手机上的胰岛素泵配对。将 Mobi 放在充电座上以重新配对。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPumpSetupConfirmDialog = false
                        Prefs(context).setPumpSetupComplete(false)
                        Prefs(context).setPumpFinderPumpMac("")
                        Prefs(context).setPumpFinderPairingCodeType("")
                        Prefs(context).setPumpFinderServiceEnabled(true)
                        Prefs(context).setCurrentPumpSid(-1)
                        PumpState.resetState(context)
                        sendMessage(MessagePaths.TO_SERVER_APP_RELOAD, "".toByteArray())
                    }
                ) {
                    Text("断开连接")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPumpSetupConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showSupportBundleDialog && supportBundleSummary != null) {
        val summary = supportBundleSummary!!
        AlertDialog(
            onDismissRequest = {
                showSupportBundleDialog = false
                supportBundleSummary = null
            },
            title = { Text("发送 PumpX2 支持包") },
            text = {
                Text("是否将 ${summary.debugFileCount} 个调试文件（共 ${formatLogLineCount(summary.totalLogLines)} 条日志，时间范围 ${summary.rangeStart} - ${summary.rangeEnd}）发送给开发者以获取帮助？")
            },
            confirmButton = {
                TextButton(onClick = {
                    sendSupportBundleEmail(context)
                    showSupportBundleDialog = false
                    supportBundleSummary = null
                }) {
                    Text("发送邮件")
                }

                TextButton(onClick = {
                    shareSupportBundle(context)
                    showSupportBundleDialog = false
                    supportBundleSummary = null
                }) {
                    Text("分享")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSupportBundleDialog = false
                    supportBundleSummary = null
                }) {
                    Text("取消")
                }
            }
        )
    }

    // Sync Time Dialog
    if (showSyncTimeDialog) {
        AlertDialog(
            onDismissRequest = { showSyncTimeDialog = false },
            title = { Text("同步胰岛素泵时间") },
            text = { Text("将胰岛素泵内部时钟设置为当前手机时间？") },
            confirmButton = {
                TextButton(onClick = {
                    sendPumpCommands(
                        SendType.STANDARD,
                        listOf(ChangeTimeDateRequest(Instant.now()))
                    )
                    showSyncTimeDialog = false
                    Toast.makeText(context, "胰岛素泵时间同步已发送", Toast.LENGTH_SHORT).show()
                }) {
                    Text("同步")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSyncTimeDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showDeviceRoleDialog) {
        val newRole = when (currentDeviceRole) {
            DeviceRole.PUMP_HOST -> DeviceRole.CLIENT
            DeviceRole.CLIENT -> DeviceRole.PUMP_HOST
        }
        val newRoleLabel = when (newRole) {
            DeviceRole.PUMP_HOST -> "手机（胰岛素泵主机）"
            DeviceRole.CLIENT -> "手表（胰岛素泵主机）"
        }
        val walkthrough = when (newRole) {
            DeviceRole.CLIENT ->
                "手机将作为客户端重启，手表接管为胰岛素泵主机。将 Mobi 放在充电座上以重新配对。"
            DeviceRole.PUMP_HOST ->
                "手机将作为胰岛素泵主机重启，手表切换为客户端。将 Mobi 放在充电座上以重新配对。"
        }
        AlertDialog(
            onDismissRequest = { showDeviceRoleDialog = false },
            title = { Text("将胰岛素泵主机切换为 $newRoleLabel？") },
            text = { Text(walkthrough) },
            confirmButton = {
                TextButton(onClick = {
                    showDeviceRoleDialog = false
                    val activity = context as? Activity
                    if (activity != null) {
                        // `switchDeviceRole` calls `activity.recreate()`; the
                        // current Composable tree is discarded before any state
                        // write here would be observable, so we don't touch
                        // `currentDeviceRole` — the re-created Activity reads
                        // the fresh pref.
                        switchDeviceRole(activity, newRole)
                    } else {
                        Toast.makeText(context, "无法切换角色：无 Activity 上下文", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("切换")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeviceRoleDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showRescueDialog) {
        AlertDialog(
            onDismissRequest = { showRescueDialog = false },
            title = { Text("重置并重新开始？") },
            text = {
                Text("强制此手机作为胰岛素泵主机，清除胰岛素泵配对，并要求手表切换为客户端。将 Mobi 放在充电座上以重新配对。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showRescueDialog = false
                    val activity = context as? Activity
                    if (activity != null) {
                        rescueResetThisDevice(activity)
                    } else {
                        Toast.makeText(context, "无法重置：无 Activity 上下文", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("重置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRescueDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Play Sound Dialog
    if (showPlaySoundDialog) {
        AlertDialog(
            onDismissRequest = { showPlaySoundDialog = false },
            title = { Text("查找我的胰岛素泵") },
            text = { Text("在胰岛素泵上播放声音？") },
            confirmButton = {
                TextButton(onClick = {
                    sendPumpCommands(
                        SendType.STANDARD,
                        listOf(PlaySoundRequest())
                    )
                    showPlaySoundDialog = false
                    Toast.makeText(context, "正在胰岛素泵上播放声音", Toast.LENGTH_SHORT).show()
                }) {
                    Text("播放声音")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPlaySoundDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun SettingsDefaultPreview() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            Settings(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
            )
        }
    }
}
