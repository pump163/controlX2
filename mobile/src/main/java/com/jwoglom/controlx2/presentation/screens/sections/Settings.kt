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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource

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
                HeaderLine(context.getString(R.string.settings_settings))
                Divider()
            }

            item {
                VersionInfo(context)
                Divider()
            }

            item {
                com.pumppan.controlx2.PumppanLanguageRow()
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.settings_send_support_bundle)) },
                    supportingContent = { Text(context.getString(R.string.settings_generate_debug_logs_zip)) },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Help,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable {
                        val summary = getSupportBundleSummary(context)
                        if (summary == null) {
                            Toast.makeText(context, context.getString(R.string.settings_no_debug_logs_are_available_to_share), Toast.LENGTH_SHORT).show()
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
                        headlineContent = { Text(context.getString(R.string.settings_enable_ctrl_x2_service)) },
                        supportingContent = { Text(context.getString(R.string.settings_start_background_service)) },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = context.getString(R.string.settings_start_icon),
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
                ListItem(
                    headlineContent = { Text(context.getString(R.string.settings_force_service_reload)) },
                    supportingContent = { Text(context.getString(R.string.settings_restarts_background_service)) },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = context.getString(R.string.settings_reload_icon),
                        )
                    },
                    modifier = Modifier.clickable {
                        sendMessage(MessagePaths.TO_SERVER_FORCE_RELOAD, "".toByteArray())
                    }
                )
                Divider()
            }

            item {
                var bolusButton by remember { mutableStateOf(Prefs(context).showBolusNotificationButton()) }
                ListItem(
                    headlineContent = { Text(context.getString(R.string.settings_reload_icon_2)) },
                    supportingContent = {
                        Text(
                            if (bolusButton) context.getString(R.string.settings_reload_icon_3)
                            else context.getString(R.string.settings_reload_icon_4)
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (bolusButton) Icons.Filled.NotificationsActive else Icons.Filled.Notifications,
                            contentDescription = stringResource(R.string.settings_reload_icon_5),
                        )
                    },
                    modifier = Modifier.clickable {
                        bolusButton = !bolusButton
                        Prefs(context).setShowBolusNotificationButton(bolusButton)
                        sendMessage(MessagePaths.TO_SERVER_APPLY_RUNTIME_PREFS, "".toByteArray())
                    }
                )
                Divider()
            }

            item {
                var tempRateButton by remember { mutableStateOf(Prefs(context).showTempRateNotificationButton()) }
                ListItem(
                    headlineContent = { Text(context.getString(R.string.settings_reload_icon_6)) },
                    supportingContent = {
                        Text(
                            if (tempRateButton) context.getString(R.string.settings_reload_icon_7)
                            else context.getString(R.string.settings_reload_icon_8)
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (tempRateButton) Icons.Filled.NotificationsActive else Icons.Filled.Notifications,
                            contentDescription = stringResource(R.string.settings_reload_icon_9),
                        )
                    },
                    modifier = Modifier.clickable {
                        tempRateButton = !tempRateButton
                        Prefs(context).setShowTempRateNotificationButton(tempRateButton)
                        sendMessage(MessagePaths.TO_SERVER_APPLY_RUNTIME_PREFS, "".toByteArray())
                    }
                )
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.settings_reconfigure_pump)) },
                    supportingContent = { Text(context.getString(R.string.settings_disconnect_and_re_pair_with_pump)) },
                    leadingContent = {
                        Icon(
                            painterResource(R.drawable.pump),
                            tint = Color.Unspecified,
                            contentDescription = context.getString(R.string.settings_icon),
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
                    headlineContent = { Text(context.getString(R.string.settings_reconfigure_app)) },
                    supportingContent = { Text(context.getString(R.string.settings_toggle_insulin_delivery_actions)) },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = context.getString(R.string.settings_icon),
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
                        DeviceRole.PUMP_HOST -> context.getString(R.string.settings_phone_pump_host)
                        DeviceRole.CLIENT -> context.getString(R.string.settings_watch_pump_host)
                    }
                    ListItem(
                        headlineContent = { Text(context.getString(R.string.settings_pump_host_device)) },
                        supportingContent = { Text(context.getString(R.string.settings_currently_role_label, roleLabel)) },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Devices,
                                contentDescription = context.getString(R.string.settings_device_role_icon),
                            )
                        },
                        modifier = Modifier.clickable {
                            showDeviceRoleDialog = true
                        }
                    )
                    Divider()
                    ListItem(
                        headlineContent = { Text(context.getString(R.string.settings_reset_start_over_on_device)) },
                        supportingContent = { Text(context.getString(R.string.settings_force_pump_host_clear_bond)) },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Devices,
                                contentDescription = context.getString(R.string.settings_reset_icon),
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
                    headlineContent = { Text(context.getString(R.string.settings_nightscout)) },
                    supportingContent = { Text(context.getString(R.string.settings_config_nightscout_sync)) },
                    leadingContent = {
                        Icon(
                            Icons.Filled.CloudSync,
                            contentDescription = context.getString(R.string.settings_nightscout_icon),
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
                    supportingContent = { Text(context.getString(R.string.settings_config_xdrip_broadcasts)) },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Sync,
                            contentDescription = context.getString(R.string.settings_x_drip_icon),
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
                    headlineContent = { Text(context.getString(R.string.settings_debug_options)) },
                    supportingContent = { Text(context.getString(R.string.settings_perform_debug_opts)) },
                    leadingContent = {
                        Icon(
                            Icons.Filled.DeveloperMode,
                            contentDescription = context.getString(R.string.settings_icon),
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

    if (showSupportBundleDialog && supportBundleSummary != null) {
        val summary = supportBundleSummary!!
        AlertDialog(
            onDismissRequest = {
                showSupportBundleDialog = false
                supportBundleSummary = null
            },
            title = { Text(context.getString(R.string.settings_send_pump_x2_support_bundle)) },
            text = {
                Text("Send ${summary.debugFileCount} debug files with ${formatLogLineCount(summary.totalLogLines)} total logs from ${summary.rangeStart} - ${summary.rangeEnd} to the developers for assistance?")
            },
            confirmButton = {
                TextButton(onClick = {
                    sendSupportBundleEmail(context)
                    showSupportBundleDialog = false
                    supportBundleSummary = null
                }) {
                    Text(context.getString(R.string.settings_send_email))
                }

                TextButton(onClick = {
                    shareSupportBundle(context)
                    showSupportBundleDialog = false
                    supportBundleSummary = null
                }) {
                    Text(context.getString(R.string.settings_share))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSupportBundleDialog = false
                    supportBundleSummary = null
                }) {
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
