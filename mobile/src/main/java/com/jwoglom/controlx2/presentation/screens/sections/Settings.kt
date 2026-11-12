@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3Api::class)

package com.jwoglom.controlx2.presentation.screens.sections

import android.widget.Toast
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
// import androidx.compose.material.icons.filled.Help // 已搬去 Debug 页
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

import com.jwoglom.controlx2.shared.FeatureFlag
import com.jwoglom.controlx2.shared.MessagePaths
import com.jwoglom.controlx2.shared.enums.DeviceRole
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.controlx2.util.AppVersionCheck
import com.jwoglom.controlx2.util.AppVersionInfo
import com.jwoglom.controlx2.util.rescueResetThisDevice
import com.jwoglom.controlx2.util.switchDeviceRole
import com.pumppan.controlx2.PumppanPrefs
import com.pumppan.controlx2.scheduled.ScheduledBolusChain
import com.pumppan.controlx2.scheduled.ScheduledBolusRuntime
import com.pumppan.controlx2.ui.*
import com.jwoglom.pumpx2.pump.PumpState
import com.jwoglom.pumpx2.pump.messages.Message
// import com.jwoglom.pumpx2.pump.messages.request.control.ChangeTimeDateRequest // 已移至控制页底部
// import com.jwoglom.pumpx2.pump.messages.request.control.PlaySoundRequest // 已移至控制页底部
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
// import java.time.Instant // 已移至控制页底部
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/** 组间分隔线：与控制页 GroupBoundary 同规格（1.5dp、colorScheme.outline、无额外留白）。 */
@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        thickness = 1.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

/** 组内行间分隔线：与控制页 GroupDivider 同规格（0.5dp、colorScheme.outlineVariant）。 */
@Composable
private fun SettingsRowDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

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

//    // 已移至控制页底部
//    var showSyncTimeDialog by remember { mutableStateOf(false) }
//    var showPlaySoundDialog by remember { mutableStateOf(false) }
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
                HeaderLine(context.getString(R.string.settings_settings))
                SettingsRowDivider()
            }

            item {
                VersionInfo(context)
                SettingsDivider()
            }

            item {
                com.pumppan.controlx2.PumppanLanguageRow()
                SettingsDivider()
            }

//            // 已搬去 Debug 页
//            item {
//                ListItem(
//                    headlineContent = { Text(context.getString(R.string.settings_send_support_bundle)) },
//                    supportingContent = { Text(context.getString(R.string.settings_generate_debug_logs_zip)) },
//                    leadingContent = {
//                        Icon(
//                            Icons.Filled.Help,
//                            contentDescription = null,
//                        )
//                    },
//                    modifier = Modifier.clickable {
//                        val summary = getSupportBundleSummary(context)
//                        if (summary == null) {
//                            Toast.makeText(context, context.getString(R.string.settings_no_debug_logs_are_available_to_share), Toast.LENGTH_SHORT).show()
//                        } else {
//                            supportBundleSummary = summary
//                            showSupportBundleDialog = true
//                        }
//                    }
//                )
//                Divider()
//            }

            item {
                if (!Prefs(context).serviceEnabled()) {
                    ActionRow(
                        title = context.getString(R.string.settings_enable_ctrl_x2_service),
                        subtitle = context.getString(R.string.settings_start_background_service),
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = context.getString(R.string.settings_start_icon),
                            )
                        },
                        onClick = {
                            Prefs(context).setServiceEnabled(true)
                            coroutineScope.launch {
                                delay(250)
                                // reload service, if running
                                sendMessage(MessagePaths.TO_SERVER_FORCE_RELOAD, "".toByteArray())
                                delay(250)
                                // reload main activity as fallback
                                sendMessage(MessagePaths.TO_SERVER_APP_RELOAD, "".toByteArray())
                            }
                        },
                    )
                    SettingsRowDivider()
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
//                                contentDescription = "Start icon",
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
                ActionRow(
                    title = context.getString(R.string.settings_force_service_reload),
                    subtitle = context.getString(R.string.settings_restarts_background_service),
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = context.getString(R.string.settings_reload_icon),
                        )
                    },
                    onClick = {
                        sendMessage(MessagePaths.TO_SERVER_FORCE_RELOAD, "".toByteArray())
                    },
                )
                SettingsRowDivider()
            }

//            // 已移至 Nightscout 前
//            item {
//                var bolusButton by remember { mutableStateOf(Prefs(context).showBolusNotificationButton()) }
//                ListItem(
//                    headlineContent = { Text(context.getString(R.string.settings_reload_icon_2)) },
//                    supportingContent = {
//                        Text(
//                            if (bolusButton) context.getString(R.string.settings_reload_icon_3)
//                            else context.getString(R.string.settings_reload_icon_4)
//                        )
//                    },
//                    leadingContent = {
//                        Icon(
//                            if (bolusButton) Icons.Filled.NotificationsActive else Icons.Filled.Notifications,
//                            contentDescription = stringResource(R.string.settings_reload_icon_5),
//                        )
//                    },
//                    modifier = Modifier.clickable {
//                        bolusButton = !bolusButton
//                        Prefs(context).setShowBolusNotificationButton(bolusButton)
//                        sendMessage(MessagePaths.TO_SERVER_APPLY_RUNTIME_PREFS, "".toByteArray())
//                    }
//                )
//                Divider()
//            }
//
//            item {
//                var tempRateButton by remember { mutableStateOf(Prefs(context).showTempRateNotificationButton()) }
//                ListItem(
//                    headlineContent = { Text(context.getString(R.string.settings_reload_icon_6)) },
//                    supportingContent = {
//                        Text(
//                            if (tempRateButton) context.getString(R.string.settings_reload_icon_7)
//                            else context.getString(R.string.settings_reload_icon_8)
//                        )
//                    },
//                    leadingContent = {
//                        Icon(
//                            if (tempRateButton) Icons.Filled.NotificationsActive else Icons.Filled.Notifications,
//                            contentDescription = stringResource(R.string.settings_reload_icon_9),
//                        )
//                    },
//                    modifier = Modifier.clickable {
//                        tempRateButton = !tempRateButton
//                        Prefs(context).setShowTempRateNotificationButton(tempRateButton)
//                        sendMessage(MessagePaths.TO_SERVER_APPLY_RUNTIME_PREFS, "".toByteArray())
//                    }
//                )
//                Divider()
//            }

            item {
                ActionRow(
                    title = context.getString(R.string.settings_reconfigure_pump),
                    subtitle = context.getString(R.string.settings_disconnect_and_re_pair_with_pump),
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.pump),
                            tint = Color.Unspecified,
                            contentDescription = context.getString(R.string.settings_icon),
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    onClick = {
                        showPumpSetupConfirmDialog = true
                    },
                )
                SettingsRowDivider()
            }

            item {
                ActionRow(
                    title = context.getString(R.string.settings_reconfigure_app),
                    subtitle = context.getString(R.string.settings_toggle_insulin_delivery_actions),
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = context.getString(R.string.settings_icon),
                        )
                    },
                    onClick = {
                        Prefs(context).setAppSetupComplete(false)
                        navController?.navigate(Screen.AppSetup.route)
                    },
                )
                SettingsRowDivider()
            }

            item {
                ActionRow(
                    title = context.getString(R.string.settings_self_check),
                    subtitle = context.getString(R.string.settings_self_check_subtitle),
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = context.getString(R.string.settings_self_check_icon),
                        )
                    },
                    onClick = {
                        navController?.navigate(Screen.SettingsSelfCheck.route)
                    },
                )
            }

            item {
                var ffEnabled by remember { mutableStateOf(FeatureFlag.enabled(context,
                    FeatureFlag.BTHostSwitch
                ))}

                if (ffEnabled) {
                    SettingsRowDivider()
                    val roleLabel = when (currentDeviceRole) {
                        DeviceRole.PUMP_HOST -> context.getString(R.string.settings_phone_pump_host)
                        DeviceRole.CLIENT -> context.getString(R.string.settings_watch_pump_host)
                    }
                    ActionRow(
                        title = context.getString(R.string.settings_pump_host_device),
                        subtitle = context.getString(R.string.settings_currently_role_label, roleLabel),
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Devices,
                                contentDescription = context.getString(R.string.settings_device_role_icon),
                            )
                        },
                        onClick = {
                            showDeviceRoleDialog = true
                        },
                    )
                    SettingsRowDivider()
                    ActionRow(
                        title = context.getString(R.string.settings_reset_start_over_on_device),
                        subtitle = context.getString(R.string.settings_force_pump_host_clear_bond),
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Devices,
                                contentDescription = context.getString(R.string.settings_reset_icon),
                            )
                        },
                        onClick = {
                            showRescueDialog = true
                        },
                    )
                }
            }

            item {
                SettingsDivider()
            }

            item {
                var bolusButton by remember { mutableStateOf(Prefs(context).showBolusNotificationButton()) }
                ActionRow(
                    title = context.getString(R.string.settings_reload_icon_2),
                    subtitle =
                        if (bolusButton) context.getString(R.string.settings_reload_icon_3)
                        else context.getString(R.string.settings_reload_icon_4),
                    leadingIcon = {
                        Icon(
                            if (bolusButton) Icons.Filled.NotificationsActive else Icons.Filled.Notifications,
                            contentDescription = stringResource(R.string.settings_reload_icon_5),
                        )
                    },
                    onClick = {
                        bolusButton = !bolusButton
                        Prefs(context).setShowBolusNotificationButton(bolusButton)
                        sendMessage(MessagePaths.TO_SERVER_APPLY_RUNTIME_PREFS, "".toByteArray())
                    },
                )
                SettingsRowDivider()
            }

            item {
                var tempRateButton by remember { mutableStateOf(Prefs(context).showTempRateNotificationButton()) }
                ActionRow(
                    title = context.getString(R.string.settings_reload_icon_6),
                    subtitle =
                        if (tempRateButton) context.getString(R.string.settings_reload_icon_7)
                        else context.getString(R.string.settings_reload_icon_8),
                    leadingIcon = {
                        Icon(
                            if (tempRateButton) Icons.Filled.NotificationsActive else Icons.Filled.Notifications,
                            contentDescription = stringResource(R.string.settings_reload_icon_9),
                        )
                    },
                    onClick = {
                        tempRateButton = !tempRateButton
                        Prefs(context).setShowTempRateNotificationButton(tempRateButton)
                        sendMessage(MessagePaths.TO_SERVER_APPLY_RUNTIME_PREFS, "".toByteArray())
                    },
                )
                SettingsDivider()
            }

//            // 已注释：三项开关副标题已含首页语义，保留组标题反而不整齐
//            item {
//                ListItem(
//                    headlineContent = { Text(context.getString(R.string.settings_home_show_section)) },
//                )
//            }

            item {
                var cgmEnabled by remember { mutableStateOf(FeatureFlag.enabled(context, FeatureFlag.CgmChartVisible)) }
                ActionRow(
                    title = context.getString(R.string.settings_home_show_cgm_chart),
                    subtitle = context.getString(R.string.settings_home_show_cgm_chart_sub),
                    leadingIcon = {
                        Icon(
                            if (cgmEnabled) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        cgmEnabled = !cgmEnabled
                        FeatureFlag.set(context, FeatureFlag.CgmChartVisible, cgmEnabled)
                    },
                    modifier = Modifier.semantics {
                        stateDescription = context.getString(
                            if (cgmEnabled) R.string.pumppan_toggle_on else R.string.pumppan_toggle_off
                        )
                    },
                )
                SettingsRowDivider()
            }

            item {
                var sensorEnabled by remember { mutableStateOf(FeatureFlag.enabled(context, FeatureFlag.SensorInfoCardVisible)) }
                ActionRow(
                    title = context.getString(R.string.settings_home_show_sensor_info),
                    subtitle = context.getString(R.string.settings_home_show_sensor_info_sub),
                    leadingIcon = {
                        Icon(
                            if (sensorEnabled) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        sensorEnabled = !sensorEnabled
                        FeatureFlag.set(context, FeatureFlag.SensorInfoCardVisible, sensorEnabled)
                    },
                    modifier = Modifier.semantics {
                        stateDescription = context.getString(
                            if (sensorEnabled) R.string.pumppan_toggle_on else R.string.pumppan_toggle_off
                        )
                    },
                )
                SettingsRowDivider()
            }

            item {
                var historyEnabled by remember { mutableStateOf(FeatureFlag.enabled(context, FeatureFlag.HistoryLogSyncBarVisible)) }
                ActionRow(
                    title = context.getString(R.string.settings_home_show_history_sync),
                    subtitle = context.getString(R.string.settings_home_show_history_sync_sub),
                    leadingIcon = {
                        Icon(
                            if (historyEnabled) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        historyEnabled = !historyEnabled
                        FeatureFlag.set(context, FeatureFlag.HistoryLogSyncBarVisible, historyEnabled)
                    },
                    modifier = Modifier.semantics {
                        stateDescription = context.getString(
                            if (historyEnabled) R.string.pumppan_toggle_on else R.string.pumppan_toggle_off
                        )
                    },
                )
                SettingsDivider()
            }

            // 单独成组：自动读取历史记录 + 大剂量新页面开关（按用户要求不新增组标题，靠上下组间线区分）
            item {
                var autoFetchHistoryLogs by remember { mutableStateOf(Prefs(context).autoFetchHistoryLogs()) }
                ActionRow(
                    title = context.getString(R.string.settings_auto_fetch_history_logs),
                    subtitle = context.getString(R.string.settings_auto_fetch_history_logs_sub),
                    leadingIcon = {
                        Icon(
                            if (autoFetchHistoryLogs) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        autoFetchHistoryLogs = !autoFetchHistoryLogs
                        Prefs(context).setAutoFetchHistoryLogs(autoFetchHistoryLogs)
                    },
                    modifier = Modifier.semantics {
                        stateDescription = context.getString(
                            if (autoFetchHistoryLogs) R.string.pumppan_toggle_on else R.string.pumppan_toggle_off
                        )
                    },
                )
                SettingsRowDivider()

                // S9：大剂量新页面开关（默认关闭）；开启后导航栏中间出现入口，旧的 FAB 入口隐藏。
                // 文案随状态翻转：关闭态描述「切过去会得到什么」，开启态描述「退回去会得到什么」。
                var bolusUiV2 by remember { mutableStateOf(PumppanPrefs.bolusUiV2(context)) }
                ActionRow(
                    title = context.getString(
                        if (bolusUiV2) R.string.settings_pumppan_bolus_ui_stop
                        else R.string.settings_pumppan_bolus_ui
                    ),
                    subtitle = context.getString(
                        if (bolusUiV2) R.string.settings_pumppan_bolus_ui_stop_sub
                        else R.string.settings_pumppan_bolus_ui_sub
                    ),
                    leadingIcon = {
                        Icon(
                            if (bolusUiV2) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        bolusUiV2 = !bolusUiV2
                        PumppanPrefs.setBolusUiV2(context, bolusUiV2)
                    },
                    modifier = Modifier.semantics {
                        stateDescription = context.getString(
                            if (bolusUiV2) R.string.pumppan_toggle_on else R.string.pumppan_toggle_off
                        )
                    },
                )
                // 定时大剂量总开关：控制「大剂量 V2」里 [定时] 标签的可见性。
                // 关掉即立刻作废正在跑的链（用户口径：标签隐藏 = 链关闭）。
                var scheduled by remember { mutableStateOf(PumppanPrefs.scheduledVisible(context)) }
                ActionRow(
                    title = context.getString(R.string.settings_scheduled_visible),
                    subtitle = context.getString(R.string.settings_scheduled_visible_sub),
                    leadingIcon = {
                        Icon(
                            if (scheduled) Icons.Filled.Timer else Icons.Filled.Close,
                            contentDescription = context.getString(R.string.settings_scheduled_visible),
                        )
                    },
                    onClick = {
                        scheduled = !scheduled
                        PumppanPrefs.setScheduledVisible(context, scheduled)
                        if (!scheduled) {
                            ScheduledBolusRuntime.cancel(
                                context,
                                ScheduledBolusChain.AbortReason.FEATURE_DISABLED,
                            )
                        }
                    },
                    modifier = Modifier.semantics {
                        stateDescription = context.getString(
                            if (scheduled) R.string.pumppan_toggle_on else R.string.pumppan_toggle_off
                        )
                    },
                )                // 通知页大剂量流水开关：默认关闭、仅 X2 生效；副标题写明「重启后清空」。
                var ledgerCard by remember { mutableStateOf(PumppanPrefs.ledgerCard(context)) }
                ActionRow(
                    title = context.getString(R.string.settings_pumppan_ledger),
                    subtitle = context.getString(R.string.settings_pumppan_ledger_sub),
                    leadingIcon = {
                        Icon(
                            if (ledgerCard) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        ledgerCard = !ledgerCard
                        PumppanPrefs.setLedgerCard(context, ledgerCard)
                    },
                    modifier = Modifier.semantics {
                        stateDescription = context.getString(
                            if (ledgerCard) R.string.pumppan_toggle_on else R.string.pumppan_toggle_off
                        )
                    },
                )
                SettingsDivider()
            }

            item {
                ActionRow(
                    title = context.getString(R.string.settings_nightscout),
                    subtitle = context.getString(R.string.settings_config_nightscout_sync),
                    leadingIcon = {
                        Icon(
                            Icons.Filled.CloudSync,
                            contentDescription = context.getString(R.string.settings_nightscout_icon),
                        )
                    },
                    onClick = {
                        navigateToNightscoutSettings()
                    },
                )
                SettingsRowDivider()
            }

            item {
                ActionRow(
                    title = "xDrip",
                    subtitle = context.getString(R.string.settings_config_xdrip_broadcasts),
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Sync,
                            contentDescription = context.getString(R.string.settings_x_drip_icon),
                        )
                    },
                    onClick = {
                        navigateToXdripSettings()
                    },
                )
                SettingsDivider()
            }

//            // 已移至控制页底部
//            item {
//                ListItem(
//                    headlineContent = { Text(context.getString(R.string.settings_sync_pump_time)) },
//                    supportingContent = { Text(context.getString(R.string.settings_set_pump_s_clock_to)) },
//                    leadingContent = {
//                        Icon(
//                            Icons.Filled.Timer,
//                            contentDescription = context.getString(R.string.settings_sync_time_icon),
//                        )
//                    },
//                    modifier = Modifier.clickable {
//                        showSyncTimeDialog = true
//                    }
//                )
//                Divider()
//            }
//
//            item {
//                ListItem(
//                    headlineContent = { Text(context.getString(R.string.settings_find_my_pump)) },
//                    supportingContent = { Text(context.getString(R.string.settings_play_sound_on_pump_to_help_locate)) },
//                    leadingContent = {
//                        Icon(
//                            Icons.Filled.NotificationsActive,
//                            contentDescription = context.getString(R.string.settings_play_sound_icon),
//                        )
//                    },
//                    modifier = Modifier.clickable {
//                        showPlaySoundDialog = true
//                    }
//                )
//                Divider()
//            }

            item {
                ActionRow(
                    title = context.getString(R.string.settings_debug_options),
                    subtitle = context.getString(R.string.settings_perform_debug_opts),
                    leadingIcon = {
                        Icon(
                            Icons.Filled.DeveloperMode,
                            contentDescription = context.getString(R.string.settings_icon),
                        )
                    },
                    onClick = {
                        navigateToDebugOptions()
                    },
                )
                SettingsDivider()
            }
        }
    )

    if (showPumpSetupConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showPumpSetupConfirmDialog = false },
            title = { Text(context.getString(R.string.settings_forget_pump_query)) },
            text = {
                Text(context.getString(R.string.settings_clears_pump_bond_on_this_phone))
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
                    Text(context.getString(R.string.settings_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPumpSetupConfirmDialog = false }) {
                    Text(context.getString(R.string.settings_cancel))
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
            DeviceRole.PUMP_HOST -> context.getString(R.string.settings_phone_pump_host)
            DeviceRole.CLIENT -> context.getString(R.string.settings_watch_pump_host)
        }
        val walkthrough = when (newRole) {
            DeviceRole.CLIENT ->
                stringResource(R.string.settings_phone_restarts_as_client_and)
            DeviceRole.PUMP_HOST ->
                stringResource(R.string.settings_phone_restarts_as_pump_host_and)
        }
        AlertDialog(
            onDismissRequest = { showDeviceRoleDialog = false },
            title = { Text(context.getString(R.string.settings_switch_pump_host_to_new_role_query, newRoleLabel)) },
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
                        Toast.makeText(context, context.getString(R.string.settings_switch_role_no_activity_context), Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(context.getString(R.string.settings_switch))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeviceRoleDialog = false }) {
                    Text(context.getString(R.string.settings_cancel))
                }
            }
        )
    }

    if (showRescueDialog) {
        AlertDialog(
            onDismissRequest = { showRescueDialog = false },
            title = { Text(context.getString(R.string.settings_reset_start_over_query)) },
            text = {
                Text(context.getString(R.string.settings_force_pump_host_watch_client))
            },
            confirmButton = {
                TextButton(onClick = {
                    showRescueDialog = false
                    val activity = context as? Activity
                    if (activity != null) {
                        rescueResetThisDevice(activity)
                    } else {
                        Toast.makeText(context, context.getString(R.string.settings_unable_to_rescue_no_activity_context), Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(context.getString(R.string.settings_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRescueDialog = false }) {
                    Text(context.getString(R.string.settings_cancel))
                }
            }
        )
    }

//    // 已搬去 Debug 页
//    if (showSupportBundleDialog && supportBundleSummary != null) {
//        val summary = supportBundleSummary!!
//        AlertDialog(
//            onDismissRequest = {
//                showSupportBundleDialog = false
//                supportBundleSummary = null
//            },
//            title = { Text(context.getString(R.string.settings_send_pump_x2_support_bundle)) },
//            text = {
//                Text("Send ${summary.debugFileCount} debug files with ${formatLogLineCount(summary.totalLogLines)} total logs from ${summary.rangeStart} - ${summary.rangeEnd} to the developers for assistance?")
//            },
//            confirmButton = {
//                TextButton(onClick = {
//                    sendSupportBundleEmail(context)
//                    showSupportBundleDialog = false
//                    supportBundleSummary = null
//                }) {
//                    Text(context.getString(R.string.settings_send_email))
//                }
//
//                TextButton(onClick = {
//                    shareSupportBundle(context)
//                    showSupportBundleDialog = false
//                    supportBundleSummary = null
//                }) {
//                    Text(context.getString(R.string.settings_share))
//                }
//            },
//            dismissButton = {
//                TextButton(onClick = {
//                    showSupportBundleDialog = false
//                    supportBundleSummary = null
//                }) {
//                    Text(context.getString(R.string.settings_cancel))
//                }
//            }
//        )
//    }

//    // 已移至控制页底部
//    // Sync Time Dialog
//    if (showSyncTimeDialog) {
//        AlertDialog(
//            onDismissRequest = { showSyncTimeDialog = false },
//            title = { Text(context.getString(R.string.settings_sync_pump_time_2)) },
//            text = { Text(context.getString(R.string.settings_set_pump_s_internal_clock_to_query)) },
//            confirmButton = {
//                TextButton(onClick = {
//                    sendPumpCommands(
//                        SendType.STANDARD,
//                        listOf(ChangeTimeDateRequest(Instant.now()))
//                    )
//                    showSyncTimeDialog = false
//                    Toast.makeText(context, context.getString(R.string.settings_pump_time_sync_sent), Toast.LENGTH_SHORT).show()
//                }) {
//                    Text(context.getString(R.string.settings_sync))
//                }
//            },
//            dismissButton = {
//                TextButton(onClick = { showSyncTimeDialog = false }) {
//                    Text(context.getString(R.string.settings_cancel))
//                }
//            }
//        )
//    }
//
//    // Play Sound Dialog
//    if (showPlaySoundDialog) {
//        AlertDialog(
//            onDismissRequest = { showPlaySoundDialog = false },
//            title = { Text(context.getString(R.string.settings_find_my_pump_2)) },
//            text = { Text(context.getString(R.string.settings_play_sound_on_pump_query)) },
//            confirmButton = {
//                TextButton(onClick = {
//                    sendPumpCommands(
//                        SendType.STANDARD,
//                        listOf(PlaySoundRequest())
//                    )
//                    showPlaySoundDialog = false
//                    Toast.makeText(context, context.getString(R.string.settings_playing_sound_on_pump), Toast.LENGTH_SHORT).show()
//                }) {
//                    Text(context.getString(R.string.settings_play_sound))
//                }
//            },
//            dismissButton = {
//                TextButton(onClick = { showPlaySoundDialog = false }) {
//                    Text(context.getString(R.string.settings_cancel))
//                }
//            }
//        )
//    }

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
