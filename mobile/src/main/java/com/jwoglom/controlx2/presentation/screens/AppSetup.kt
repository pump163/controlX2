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
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R

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
        stringResource(R.string.setup_app_setup),
        buttonContent = {
            Button(
                onClick = {
                    if (navController?.popBackStack() == false) {
                        navController.navigate(Screen.PumpSetup.route)
                    }
                    Prefs(context).setAppSetupComplete(false)
                }
            ) {
                Text(stringResource(R.string.setup_back))
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
                Text(stringResource(R.string.setup_continue))
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
//            Divider()
//        }
        item {
            ListItem(
                headlineContent = {
                    Text(context.getString(R.string.setup_insulin_delivery_actions))
                },
                supportingContent = {
                    Text(context.getString(R.string.setup_allow_remote_boluses_from_phone_watch))
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
            Divider()
        }
        item {
            if (insulinDeliveryActions || preview) {
                ListItem(
                    headlineContent = {
                        Text(context.getString(R.string.setup_bolus_confirmation_threshold))
                    },
                    supportingContent = {
                        Text(
                            bolusConfirmationInsulinThreshold.let {
                                if (it == 0.0) stringResource(R.string.setup_require_confirmation_for_all_boluses)
                                else stringResource(R.string.setup_require_confirmation_for_bolus_above_u, twoDecimalPlaces(it))
                            }
                        )
                    },
                    trailingContent = {
                        Text(
                            text = bolusConfirmationInsulinThreshold.let { if (it == 0.0) stringResource(R.string.setup_always) else stringResource(R.string.setup_twodecimal_u, twoDecimalPlaces(it)) },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.clickable {
                        showBolusThresholdDialog = true
                    }
                )
                Divider()
            }
        }
        item {
            if (insulinDeliveryActions || preview) {
                ListItem(
                    headlineContent = {
                        Text(context.getString(R.string.setup_wear_auto_approve_timeout))
                    },
                    supportingContent = {
                        Text(
                            when (wearAutoApproveTimeout) {
                                0 -> context.getString(R.string.setup_never_auto_approve)
                                else -> context.getString(R.string.setup_auto_approve_after_s_if_not_canceled, wearAutoApproveTimeout)
                            }
                        )
                    },
                    trailingContent = {
                        Text(
                            text = when (wearAutoApproveTimeout) {
                                0 -> stringResource(R.string.setup_never)
                                60 -> stringResource(R.string.setup_1_min)
                                300 -> stringResource(R.string.setup_5_min)
                                else -> stringResource(R.string.setup_wear_autoapprove_timeout_s, wearAutoApproveTimeout)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.clickable {
                        showWearAutoApproveDialog = true
                    }
                )
                Divider()
            }
        }
        item {
            ListItem(
                headlineContent = {
                    Text(context.getString(R.string.setup_check_for_updates))
                },
                supportingContent = {
                    Text(context.getString(R.string.setup_auto_check_for_ctrl_x2_updates))
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
            Divider()
        }
        item {
            ListItem(
                headlineContent = {
                    Text(context.getString(R.string.setup_auto_fetch_history_logs))
                },
                supportingContent = {
                    Text(context.getString(R.string.setup_fetching_history_logs_allows_cgm_graph))
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
            Divider()
        }
        item {
            ListItem(
                headlineContent = {
                    Text(context.getString(R.string.setup_glucose_unit))
                },
                supportingContent = {
                    Text(context.getString(R.string.setup_choose_bg_mgdl_or_mmol))
                },
                trailingContent = {
                    Text(
                        text = glucoseUnit?.abbreviation ?: stringResource(R.string.setup_not_set),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                modifier = Modifier.clickable {
                    showGlucoseUnitDialog = true
                }
            )
            Divider()
        }
    }

    // Glucose Unit Selection Dialog
    if (showGlucoseUnitDialog) {
        AlertDialog(
            onDismissRequest = { showGlucoseUnitDialog = false },
            title = { Text(stringResource(R.string.setup_select_glucose_unit)) },
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
                    Text(stringResource(R.string.setup_cancel))
                }
            }
        )
    }

    // Insulin Delivery Warning Dialog
    if (showInsulinWarningDialog) {
        AlertDialog(
            onDismissRequest = { showInsulinWarningDialog = false },
            title = { Text(stringResource(R.string.setup_warning)) },
            text = {
                Text(stringResource(R.string.setup_warning_soft_is_unofficial_experimental))
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
                    Text(stringResource(R.string.setup_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showInsulinWarningDialog = false }) {
                    Text(stringResource(R.string.setup_cancel))
                }
            }
        )
    }

    // Updates Warning Dialog
    if (showUpdatesWarningDialog) {
        AlertDialog(
            onDismissRequest = { showUpdatesWarningDialog = false },
            title = { Text(stringResource(R.string.setup_disable_update_checks)) },
            text = {
                Text(stringResource(R.string.setup_regularly_check_ctrl_x2_updates))
            },
            confirmButton = {
                Button(onClick = {
                    checkForUpdates = false
                    Prefs(context).setCheckForUpdates(false)
                    showUpdatesWarningDialog = false
                }) {
                    Text(stringResource(R.string.setup_disable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdatesWarningDialog = false }) {
                    Text(stringResource(R.string.setup_cancel))
                }
            }
        )
    }

    // Wear Auto-Approve Timeout Dialog
    if (showWearAutoApproveDialog) {
        val options = listOf(
            0 to stringResource(R.string.setup_never_require_manual_confirmation),
            30 to stringResource(R.string.setup_30_seconds),
            60 to stringResource(R.string.setup_1_minute),
            300 to stringResource(R.string.setup_5_minutes),
        )
        AlertDialog(
            onDismissRequest = { showWearAutoApproveDialog = false },
            title = { Text(stringResource(R.string.setup_wear_auto_approve_timeout)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.setup_when_a_bolus_is_wear_auto_approved),
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
                    Text(stringResource(R.string.setup_cancel))
                }
            }
        )
    }

    // Bolus Threshold Dialog
    if (showBolusThresholdDialog) {
        var thresholdInput by remember { mutableStateOf(bolusConfirmationInsulinThreshold.let { if (it == 0.0) "" else "$it" }) }
        AlertDialog(
            onDismissRequest = { showBolusThresholdDialog = false },
            title = { Text(stringResource(R.string.setup_bolus_confirmation_threshold)) },
            text = {
                OutlinedTextField(
                    value = thresholdInput,
                    onValueChange = { thresholdInput = it },
                    label = { Text(stringResource(R.string.setup_threshold_units)) },
                    supportingText = { Text(stringResource(R.string.setup_enter_0_to_always_require_confirmation)) },
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
                    Text(stringResource(R.string.setup_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBolusThresholdDialog = false }) {
                    Text(stringResource(R.string.setup_cancel))
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
