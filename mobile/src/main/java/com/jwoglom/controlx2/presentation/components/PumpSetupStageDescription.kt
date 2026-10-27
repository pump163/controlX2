@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3Api::class)
package com.jwoglom.controlx2.presentation.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.presentation.screens.PumpSetupStage
import com.jwoglom.controlx2.pump.ErrorAction
import com.jwoglom.controlx2.pump.ErrorPresentation
import com.jwoglom.controlx2.pump.Severity
import com.jwoglom.controlx2.shared.MessagePaths
import com.jwoglom.controlx2.shared.presentation.intervalOf
import com.jwoglom.controlx2.shared.util.shortTimeAgo
import com.jwoglom.controlx2.shared.util.determinePumpModel
import com.jwoglom.pumpx2.pump.PumpState
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel
import com.jwoglom.pumpx2.pump.messages.models.PairingCodeType
import timber.log.Timber
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R

const val TroubleshootingStepsThresholdSeconds = 15

@Composable
fun PumpSetupStageDescription(
    initialSetup: Boolean = false,
    pairingCodeStage: @Composable () -> Unit = {},
    sendMessage: ((String, ByteArray) -> Unit)? = null,
) {
    val context = LocalContext.current
    val ds = LocalDataStore.current
    val coroutineScope = rememberCoroutineScope()

    val setupStage = ds.pumpSetupStage.observeAsState()
    val pumpFinderPumps = ds.pumpFinderPumps.observeAsState()
    val setupDeviceName = ds.setupDeviceName.observeAsState()
    val pumpReadyState = ds.pumpReadyState.observeAsState()
    val setupDeviceModel = ds.setupDeviceModel.observeAsState()
    val pumpCriticalError = ds.pumpCriticalError.observeAsState()

    var pumpConnectionWaitingSeconds by remember { mutableStateOf(0) }

    LaunchedEffect (intervalOf(1)) {
        if (setupStage.value == PumpSetupStage.PUMPX2_PUMP_CONNECTED) {
            if (pumpConnectionWaitingSeconds > 0) {
                Timber.d("PumpSetupStageProgress pumpConnectionWaitingSeconds=$pumpConnectionWaitingSeconds")
                pumpConnectionWaitingSeconds = 0
            }
        } else {
            pumpConnectionWaitingSeconds += 1
            if (pumpConnectionWaitingSeconds % 20 == 0) {
                Timber.d("PumpSetupStageProgress pumpConnectionWaitingSeconds=$pumpConnectionWaitingSeconds")
            }
        }
    }

    when (setupStage.value) {
        PumpSetupStage.PERMISSIONS_NOT_GRANTED -> {
            Line(stringResource(R.string.setup_notification_permissions))
        }
        PumpSetupStage.WAITING_PUMP_FINDER_INIT -> {
            if (Prefs(context).pumpFinderServiceEnabled()) {
                Line(stringResource(R.string.setup_waiting_for_pump_finder_library_init))
            }
        }
        PumpSetupStage.PUMP_FINDER_SEARCHING_FOR_PUMPS, PumpSetupStage.PUMPX2_SEARCHING_FOR_PUMP -> {
            if (initialSetup) {
                Line(buildAnnotatedString {
                    withStyle(
                        style = SpanStyle(
                            fontWeight = FontWeight.Bold
                        )
                    ) {
                        append(stringResource(R.string.setup_for_t_slim_x2))
                    }
                    append(stringResource(R.string.setup_open_pump_and_select))
                })
                Line(stringResource(R.string.setup_opts_device_settings_bt), bold = true)
                Line(stringResource(R.string.setup_enable_mobile_conn_pair))
                Spacer(Modifier.height(16.dp))
                Line(buildAnnotatedString {
                    withStyle(
                        style = SpanStyle(
                            fontWeight = FontWeight.Bold
                        )
                    ) {
                        append(stringResource(R.string.setup_for_mobi))
                    }
                    append(stringResource(R.string.setup_place_pump_on_wireless_charger))
                })
                Line(stringResource(R.string.setup_ensure_mobi_is_turned_on_and_charging))
                Line(stringResource(R.string.setup_take_mobi_off_charger_and_place_back_on))
                Line(stringResource(R.string.setup_use_usb_to_c_cable))
            } else {
                Line(stringResource(R.string.setup_searching_for_pump))
                Line(buildAnnotatedString {
                    withStyle(
                        style = SpanStyle(
                            fontWeight = FontWeight.Bold
                        )
                    ) {
                        append(stringResource(R.string.setup_for_t_slim_x2))
                    }
                    append(stringResource(R.string.setup_if_pump_isn_t_appearing_open_and_select))
                })
                Line(stringResource(R.string.setup_opts_device_settings_bt), bold = true)
                Line(stringResource(R.string.setup_ensure_mobile_conn_option_is_enabled))
            }
        }
        PumpSetupStage.PUMP_FINDER_SELECT_PUMP -> {
            Line(stringResource(R.string.setup_select_pump_to_connect))
            Line("")
            pumpFinderPumps.value?.forEach {
                Button(
                    onClick = {
                        Prefs(context).setPumpFinderPumpMac(it.second)
                        val nextStage = when (determinePumpModel(it.first)) {
                            KnownDeviceModel.MOBI -> PumpSetupStage.PUMP_FINDER_MOBI_PLACE_ON_CHARGING_PAD
                            else -> PumpSetupStage.PUMP_FINDER_TSLIM_CHOOSE_PAIRING_CODE_TYPE
                        }
                        ds.pumpSetupStage.value = ds.pumpSetupStage.value?.nextStage(nextStage)
                        ds.setupDeviceName.value = it.first
                    }
                ) {
                    Text(stringResource(R.string.setup_mac, it.first, it.second))
                }
                Line("")
            }
            Line("")
        }
        PumpSetupStage.PUMP_FINDER_TSLIM_CHOOSE_PAIRING_CODE_TYPE -> {
            Line(stringResource(R.string.setup_choose_correct_pair_code_type))
            Line("")
            Button(
                onClick = {
                    Prefs(context).setPumpFinderPairingCodeType(PairingCodeType.LONG_16CHAR.label)
                    ds.setupPairingCodeType.value = PairingCodeType.LONG_16CHAR
                    ds.pumpSetupStage.value =
                        ds.pumpSetupStage.value?.nextStage(PumpSetupStage.PUMP_FINDER_TSLIM_ENTER_PAIRING_CODE)
                }
            ) {
                Text(stringResource(R.string.setup_long_16_alphanumeric_characters))
            }

            Line("")

            Button(
                onClick = {
                    Prefs(context).setPumpFinderPairingCodeType(PairingCodeType.SHORT_6CHAR.label)
                    ds.setupPairingCodeType.value = PairingCodeType.SHORT_6CHAR
                    ds.pumpSetupStage.value = ds.pumpSetupStage.value?.nextStage(PumpSetupStage.PUMP_FINDER_TSLIM_ENTER_PAIRING_CODE)
                }
            ) {
                Text(stringResource(R.string.setup_short_6_numbers))
            }

            Line("")
            when (ds.setupDeviceName.value?.let { determinePumpModel(it) }) {
                KnownDeviceModel.TSLIM_X2 -> {
                    Line(buildAnnotatedString {
                        withStyle(
                            style = SpanStyle(
                                fontWeight = FontWeight.Bold
                            )
                        ) {
                            append(stringResource(R.string.setup_for_t_slim_x2))
                        }
                        append(stringResource(R.string.setup_open_pairing_code_and_pair))
                    })
                }
                else -> {}
            }
        }
        PumpSetupStage.PUMP_FINDER_MOBI_PLACE_ON_CHARGING_PAD -> {
            Line(buildAnnotatedString {
                withStyle(
                    style = SpanStyle(
                        fontWeight = FontWeight.Bold
                    )
                ) {
                    append(stringResource(R.string.setup_for_mobi))
                }
                append(stringResource(R.string.setup_place_mobi_on_charging_pad))
            })
            Line(stringResource(R.string.setup_ensure_is_turned_on_and_charging))

            LaunchedEffect(pumpReadyState.value) {
                when {
                    pumpReadyState.value?.shouldPickUpAndTap() == true -> {
                        ds.pumpSetupStage.value = ds.pumpSetupStage.value?.nextStage(PumpSetupStage.PUMP_FINDER_MOBI_PICK_UP_AND_TAP)
                    }
                    pumpReadyState.value?.shouldEnterPinCode() == true -> {
                        Prefs(context).setPumpFinderPairingCodeType(PairingCodeType.SHORT_6CHAR.label)
                        ds.setupPairingCodeType.value = PairingCodeType.SHORT_6CHAR
                        ds.pumpSetupStage.value = ds.pumpSetupStage.value?.nextStage(PumpSetupStage.PUMP_FINDER_MOBI_ENTER_PAIRING_CODE)
                    }
                    else -> {}
                }
            }
        }
        PumpSetupStage.PUMP_FINDER_MOBI_PICK_UP_AND_TAP -> {
            Line(buildAnnotatedString {
                withStyle(
                    style = SpanStyle(
                        fontWeight = FontWeight.Bold
                    )
                ) {
                    append(stringResource(R.string.setup_for_mobi))
                }
                append(stringResource(R.string.setup_pump_was_detected))
            })
            Line(stringResource(R.string.setup_pick_up_pump_wait_a_second))

            LaunchedEffect(pumpReadyState.value) {
                when {
                    pumpReadyState.value?.shouldPlaceOnChargingPad() == true -> {
                        ds.pumpSetupStage.value = ds.pumpSetupStage.value?.nextStage(PumpSetupStage.PUMP_FINDER_MOBI_PLACE_ON_CHARGING_PAD)
                    }
                    pumpReadyState.value?.shouldEnterPinCode() == true -> {
                        Prefs(context).setPumpFinderPairingCodeType(PairingCodeType.SHORT_6CHAR.label)
                        ds.setupPairingCodeType.value = PairingCodeType.SHORT_6CHAR
                        ds.pumpSetupStage.value = ds.pumpSetupStage.value?.nextStage(PumpSetupStage.PUMP_FINDER_MOBI_ENTER_PAIRING_CODE)
                    }
                    else -> {}
                }
            }
        }
        PumpSetupStage.PUMP_FINDER_TSLIM_ENTER_PAIRING_CODE,
        PumpSetupStage.PUMP_FINDER_MOBI_ENTER_PAIRING_CODE,
        PumpSetupStage.PUMPX2_WAITING_FOR_PAIRING_CODE,
        PumpSetupStage.PUMPX2_INVALID_PAIRING_CODE -> {
            if (initialSetup) {
                pairingCodeStage()
            } else {
                if (setupStage.value == PumpSetupStage.PUMPX2_INVALID_PAIRING_CODE) {
                    Line(buildAnnotatedString {
                        withStyle(
                            style = SpanStyle(
                                color = Color.Red,
                                fontWeight = FontWeight.Bold
                            )
                        ) {
                            append(stringResource(R.string.setup_pair_code_was_invalid))
                        }
                        append(stringResource(R.string.setup_code_entered_incorrectly_timeout))
                        if (initialSetup) {
                            withStyle(
                                style = SpanStyle(
                                    color = Color.Red,
                                    fontWeight = FontWeight.Bold
                                )
                            ) {
                                append(stringResource(R.string.setup_to_resolve_issue_re_pair))
                            }
                        } else {
                            withStyle(
                                style = SpanStyle(
                                    color = Color.Red,
                                    fontWeight = FontWeight.Bold
                                )
                            ) {
                                append(stringResource(R.string.setup_to_resolve_issue_reconfig_pump))
                            }
                        }
                    })
                } else {
                    Line(stringResource(R.string.setup_initial_conn_made_to_attempting_pair, (setupDeviceName.value ?: "")))
                }
            }
        }
        PumpSetupStage.WAITING_PUMPX2_INIT -> {
            if (Prefs(context).serviceEnabled()) {
                Line(stringResource(R.string.setup_waiting_for_library_init))
            }
        }
        PumpSetupStage.PUMPX2_PUMP_DISCONNECTED -> {
            Line(stringResource(R.string.setup_disc_from_reconn, (setupDeviceName.value ?: "")))
        }
        PumpSetupStage.PUMPX2_PUMP_DISCOVERED -> {
            Line(stringResource(R.string.setup_connecting_to, (setupDeviceName.value ?: "")))
        }
        PumpSetupStage.PUMPX2_PUMP_MODEL_METADATA -> {
            Line(stringResource(R.string.setup_connecting_to_device_model, (setupDeviceName.value ?: ""), (setupDeviceModel.value ?: "")))
        }
        PumpSetupStage.PUMPX2_INITIAL_PUMP_CONNECTION -> {
            Line(stringResource(R.string.setup_initial_conn_made_to, (setupDeviceName.value ?: "")))
        }
        PumpSetupStage.PUMPX2_PUMP_CONNECTED -> {
            if (initialSetup) {
                Line(stringResource(R.string.setup_connected_to, (setupDeviceName.value ?: "")), bold = true)
                Spacer(modifier = Modifier.height(16.dp))
                Line(stringResource(R.string.setup_press_next_to_cont))
            }
        }
        else -> {}
    }

    if (setupStage.value != PumpSetupStage.PUMPX2_PUMP_CONNECTED) {
        val errorState = pumpCriticalError.value
        // Decide visibility per tier. TRANSIENT errors stay hidden until threshold + time gate.
        val shouldShowError = errorState != null && when (errorState.presentation.severity) {
            Severity.TRANSIENT -> errorState.occurrences >= errorState.presentation.occurrenceThreshold &&
                java.time.Duration.between(errorState.firstSeenAt, java.time.Instant.now()).toMillis() >= errorState.presentation.timeThresholdMs
            Severity.ACTIONABLE, Severity.FATAL -> errorState.occurrences >= errorState.presentation.occurrenceThreshold
        }

        if (pumpConnectionWaitingSeconds > TroubleshootingStepsThresholdSeconds &&
            // Suppress the generic troubleshooting list when we already have a tiered card up
            // for an ACTIONABLE/FATAL error — it has its own targeted remediation.
            !(shouldShowError && errorState!!.presentation.severity != Severity.TRANSIENT)) {
            Spacer(Modifier.height(16.dp))
            Line(stringResource(R.string.setup_troubleshooting_steps), bold = true)
            when (setupStage.value) {
                PumpSetupStage.WAITING_PUMPX2_INIT -> {
                    if (!Prefs(context).serviceEnabled()) {
                        Line(stringResource(R.string.setup_enable_ctrl_x2_service_settings))
                    }
                    Line(stringResource(R.string.setup_toggle_bt_on_and_off))
                    Line(stringResource(R.string.setup_restart_ctrl_x2_app_via_app_info))
                    Line(stringResource(R.string.setup_ensure_ctrl_x2_app_perm))
                    Line(stringResource(R.string.setup_clear_data_reset_app_settings))
                }
                else -> {
                    Line(stringResource(R.string.setup_toggle_bt_on_and_off))
                    Line(stringResource(R.string.setup_force_stop_tconnect_app))
                }
            }
        }
        if (shouldShowError) {
            // shouldShowError implies errorState != null (see definition above).
            Spacer(Modifier.height(16.dp))
            TieredCriticalErrorCard(
                state = errorState!!,
                pumpModel = setupDeviceName.value?.let { determinePumpModel(it) },
                sendMessage = sendMessage,
            )
        }
        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(16.dp))
    }
}

private fun colorForSeverity(severity: Severity): Pair<Color, Color> = when (severity) {
    // (background, text) — chosen to be readable against both light and dark themes.
    Severity.TRANSIENT -> Color(0xFFEEEEEE) to Color(0xFF424242)
    Severity.ACTIONABLE -> Color(0xFFFFF3CD) to Color(0xFF8B6E0F)
    Severity.FATAL -> Color(0xFFFFEBEE) to Color(0xFFB71C1C)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TieredCriticalErrorCard(
    state: com.jwoglom.controlx2.presentation.PumpCriticalErrorState,
    pumpModel: KnownDeviceModel?,
    sendMessage: ((String, ByteArray) -> Unit)?,
) {
    val context = LocalContext.current
    val (bg, fg) = colorForSeverity(state.presentation.severity)
    var detailsExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = state.presentation.headline,
            color = fg,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyLarge,
        )
        // Body, with per-model branching for PAIRING_CANNOT_BEGIN — Mobi and t:slim X2
        // have different physical pairing flows.
        if (state.presentation.name == "PAIRING_CANNOT_BEGIN") {
            Text(text = state.presentation.body, color = fg)
            Spacer(Modifier.height(4.dp))
            when (pumpModel) {
                KnownDeviceModel.MOBI -> {
                    Text(stringResource(R.string.setup_force_stop_tconnect_app_2), color = fg)
                    Text(stringResource(R.string.setup_force_stop_tconnect_app_3), color = fg)
                    Text(stringResource(R.string.setup_force_stop_tconnect_app_4), color = fg)
                }
                else -> {
                    // Default to t:slim X2 instructions when model is unknown.
                    Text(stringResource(R.string.setup_force_stop_tconnect_app_5), color = fg)
                    Text(stringResource(R.string.setup_force_stop_tconnect_app_6), color = fg)
                    Text(stringResource(R.string.setup_force_stop_tconnect_app_7), color = fg)
                }
            }
        } else {
            Text(text = state.presentation.body, color = fg)
        }

        // Only render an elapsed-time line when the last sighting is meaningfully old.
        // Anything fresher than ~60s reads as noise next to a live error card.
        val elapsedMs = java.time.Duration.between(state.lastSeenAt, java.time.Instant.now()).toMillis()
        if (elapsedMs >= 60_000) {
            Text(
                text = stringResource(R.string.setup_force_stop_tconnect_app_8, shortTimeAgo(state.lastSeenAt)),
                color = fg,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.occurrences > 1) {
            Text(
                text = stringResource(R.string.setup_force_stop_tconnect_app_9, state.occurrences),
                color = fg,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.presentation.actions.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            // Primary = first action (filled Button). Rest are TextButtons.
            // FlowRow lets buttons wrap onto a new row whole rather than splitting a single
            // label like "Enable connecti / on / sharing".
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.presentation.actions.forEachIndexed { index, action ->
                    if (index == 0) {
                        Button(onClick = { runErrorAction(context, action, state, sendMessage) }) {
                            Text(actionLabel(action))
                        }
                    } else {
                        TextButton(onClick = { runErrorAction(context, action, state, sendMessage) }) {
                            Text(actionLabel(action))
                        }
                    }
                }
            }
        }

        // FATAL tier: "Show details" is a small affordance, not an action-row competitor.
        // Sits below a thin divider so the primary action row is always visible first.
        if (state.presentation.severity == Severity.FATAL) {
            Spacer(Modifier.height(4.dp))
            Divider(color = fg.copy(alpha = 0.2f))
            TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                Text(
                    text = if (detailsExpanded) stringResource(R.string.setup_force_stop_tconnect_app_10) else stringResource(R.string.setup_force_stop_tconnect_app_11),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (detailsExpanded) {
                Text(stringResource(R.string.setup_force_stop_tconnect_app_12, state.presentation.name), color = fg, style = MaterialTheme.typography.bodySmall)
                if (state.presentation.rawMessage.isNotBlank())
                    Text(stringResource(R.string.setup_force_stop_tconnect_app_13, state.presentation.rawMessage), color = fg, style = MaterialTheme.typography.bodySmall)
                if (state.presentation.extra.isNotBlank())
                    Text(stringResource(R.string.setup_force_stop_tconnect_app_14, state.presentation.extra), color = fg, style = MaterialTheme.typography.bodySmall)
                state.presentation.errorCodeId?.let {
                    Text(stringResource(R.string.setup_force_stop_tconnect_app_15, it), color = fg, style = MaterialTheme.typography.bodySmall)
                }
                state.presentation.requestCodeId?.let {
                    Text(stringResource(R.string.setup_force_stop_tconnect_app_16, it), color = fg, style = MaterialTheme.typography.bodySmall)
                }
                state.presentation.initiatingMessageClass?.let {
                    Text(stringResource(R.string.setup_force_stop_tconnect_app_17, it), color = fg, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun actionLabel(action: ErrorAction): String = when (action) {
    is ErrorAction.Retry -> "Retry"
    is ErrorAction.RepairPump -> stringResource(R.string.setup_force_stop_tconnect_app_19)
    is ErrorAction.OpenBluetoothSettings -> stringResource(R.string.setup_force_stop_tconnect_app_20)
    is ErrorAction.OpenTconnectAppInfo -> stringResource(R.string.setup_force_stop_tconnect_app_21)
    is ErrorAction.EnableConnectionSharing -> stringResource(R.string.setup_force_stop_tconnect_app_22)
    is ErrorAction.CopyDiagnostics -> stringResource(R.string.setup_force_stop_tconnect_app_23)
}

private fun runErrorAction(
    context: Context,
    action: ErrorAction,
    state: com.jwoglom.controlx2.presentation.PumpCriticalErrorState,
    sendMessage: ((String, ByteArray) -> Unit)?,
) {
    when (action) {
        is ErrorAction.Retry -> {
            // Triggers CommService to drop and reconnect the pump session.
            sendMessage?.invoke(MessagePaths.TO_SERVER_FORCE_RELOAD, "".toByteArray())
                ?: Toast.makeText(context, context.getString(R.string.setup_force_stop_tconnect_app_24), Toast.LENGTH_SHORT).show()
        }
        is ErrorAction.RepairPump -> {
            // Mirror the "Reconfigure pump" flow from Settings.kt:408-416.
            try {
                Prefs(context).setPumpSetupComplete(false)
                Prefs(context).setPumpFinderPumpMac("")
                Prefs(context).setPumpFinderPairingCodeType("")
                Prefs(context).setPumpFinderServiceEnabled(true)
                Prefs(context).setCurrentPumpSid(-1)
                PumpState.resetState(context)
                sendMessage?.invoke(MessagePaths.TO_SERVER_APP_RELOAD, "".toByteArray())
                Toast.makeText(context, context.getString(R.string.setup_force_stop_tconnect_app_25), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Timber.e(e, "Re-pair action failed")
            }
        }
        is ErrorAction.OpenBluetoothSettings -> {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, context.getString(R.string.setup_force_stop_tconnect_app_26), Toast.LENGTH_SHORT).show()
            }
        }
        is ErrorAction.OpenTconnectAppInfo -> {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:com.tandemdiabetes.tconnect"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, context.getString(R.string.setup_force_stop_tconnect_app_27), Toast.LENGTH_SHORT).show()
            }
        }
        is ErrorAction.EnableConnectionSharing -> {
            // The pref toggles the pumpx2 sharing mode; CommService reads it on next connect.
            try {
                Prefs(context).setConnectionSharingEnabled(true)
                Toast.makeText(context, context.getString(R.string.setup_force_stop_tconnect_app_28), Toast.LENGTH_SHORT).show()
                sendMessage?.invoke(MessagePaths.TO_SERVER_FORCE_RELOAD, "".toByteArray())
            } catch (e: Exception) {
                Timber.e(e, "Failed to enable connection sharing")
            }
        }
        is ErrorAction.CopyDiagnostics -> {
            val text = buildString {
                appendLine(context.getString(R.string.setup_force_stop_tconnect_app_12, state.presentation.name))
                appendLine(context.getString(R.string.setup_force_stop_tconnect_app_29, state.presentation.severity))
                appendLine(context.getString(R.string.setup_force_stop_tconnect_app_30, state.presentation.rawMessage))
                if (state.presentation.extra.isNotBlank()) appendLine(context.getString(R.string.setup_force_stop_tconnect_app_14, state.presentation.extra))
                state.presentation.errorCodeId?.let { appendLine(context.getString(R.string.setup_force_stop_tconnect_app_15, it)) }
                state.presentation.requestCodeId?.let { appendLine(context.getString(R.string.setup_force_stop_tconnect_app_16, it)) }
                state.presentation.initiatingMessageClass?.let { appendLine(context.getString(R.string.setup_force_stop_tconnect_app_17, it)) }
                appendLine(context.getString(R.string.setup_force_stop_tconnect_app_31, state.occurrences))
                appendLine(context.getString(R.string.setup_force_stop_tconnect_app_32, state.firstSeenAt))
                appendLine(context.getString(R.string.setup_force_stop_tconnect_app_33, state.lastSeenAt))
            }
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.setup_force_stop_tconnect_app_34), text))
            Toast.makeText(context, context.getString(R.string.setup_force_stop_tconnect_app_35), Toast.LENGTH_SHORT).show()
        }
    }
}
