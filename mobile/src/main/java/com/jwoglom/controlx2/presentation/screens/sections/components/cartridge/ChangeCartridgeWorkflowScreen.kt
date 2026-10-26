package com.jwoglom.controlx2.presentation.screens.sections.components.cartridge

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme
import com.jwoglom.controlx2.shared.enums.BasalStatus
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.response.controlStream.DetectingCartridgeStateStreamResponse
import com.jwoglom.pumpx2.pump.messages.response.controlStream.EnterChangeCartridgeModeStateStreamResponse
import kotlinx.coroutines.Job
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R

@Composable
fun ChangeCartridgeWorkflowScreen(
    innerPadding: PaddingValues = PaddingValues(),
    basalStatus: BasalStatus?,
    inChangeCartridgeMode: Boolean,
    enterChangeCartridgeState: EnterChangeCartridgeModeStateStreamResponse?,
    detectingCartridgeState: DetectingCartridgeStateStreamResponse?,
    activeNotifications: List<Any>,
    notificationsRefreshing: Boolean,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    refreshNotifications: () -> Job,
    onDismiss: () -> Unit,
    onSuspend: () -> Unit,
    onEnter: () -> Unit,
    onExit: () -> Unit,
    onDone: () -> Unit,
    onCancelInProgress: () -> Unit,
) {
    var showSuspendConfirm by rememberSaveable { mutableStateOf(false) }
    var showCancelConfirm by rememberSaveable { mutableStateOf(false) }
    var loadingSuspend by rememberSaveable { mutableStateOf(false) }
    var loadingEnterMode by rememberSaveable { mutableStateOf(false) }

    val step = when {
        detectingCartridgeState?.isComplete == true -> ChangeCartridgeStep.DONE
        detectingCartridgeState != null -> ChangeCartridgeStep.DETECTING
        enterChangeCartridgeState?.state == EnterChangeCartridgeModeStateStreamResponse.ChangeCartridgeState.READY_TO_CHANGE ->
            ChangeCartridgeStep.PHYSICAL_CHANGE
        inChangeCartridgeMode -> ChangeCartridgeStep.WAITING_FOR_READY
        basalStatus.isSuspendedForCartridgeWorkflow() -> ChangeCartridgeStep.ENTER_MODE
        else -> ChangeCartridgeStep.SUSPEND
    }

    val hasActiveNotifications = activeNotifications.isNotEmpty()
    val startedFlow = inChangeCartridgeMode || enterChangeCartridgeState != null || detectingCartridgeState != null

    LaunchedEffect(step, basalStatus) {
        if (step != ChangeCartridgeStep.SUSPEND || basalStatus.isSuspendedForCartridgeWorkflow()) {
            loadingSuspend = false
        }
        if (step != ChangeCartridgeStep.ENTER_MODE && step != ChangeCartridgeStep.WAITING_FOR_READY) {
            loadingEnterMode = false
        }
    }

    CartridgeWorkflowScreen(
        title = stringResource(R.string.cartridge_change_cartridge),
        innerPadding = innerPadding,
        stepInfo = WizardStepInfo(step.stepNumber, 6),
        canCancel = true,
        onCancel = {
            if (startedFlow && step != ChangeCartridgeStep.DONE) {
                showCancelConfirm = true
            } else {
                onDismiss()
            }
        },
        body = {
            when (step) {
                ChangeCartridgeStep.SUSPEND -> {
                    Text(stringResource(R.string.cartridge_important), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.cartridge_change_requires_suspend),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                ChangeCartridgeStep.ENTER_MODE -> {
                    Text(stringResource(R.string.cartridge_step_2_prepare_pump), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.cartridge_clear_active_notifications_first), style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    CartridgeNotificationsPanel(
                        notifications = activeNotifications,
                        refreshing = notificationsRefreshing,
                        sendPumpCommands = sendPumpCommands,
                        refreshNotifications = refreshNotifications,
                    )
                    if (hasActiveNotifications) {
                        NotificationsBlockingWarning(stringResource(R.string.cartridge_clear_notifications_before_change))
                    }
                }
                ChangeCartridgeStep.WAITING_FOR_READY -> {
                    Text(stringResource(R.string.cartridge_step_3_waiting_for_pump), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.cartridge_pump_preparing_for_change), style = MaterialTheme.typography.bodyLarge)
                }
                ChangeCartridgeStep.PHYSICAL_CHANGE -> {
                    Text(stringResource(R.string.cartridge_step_4_replace_cartridge), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.cartridge_remove_old_cartridge), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.cartridge_insert_new_cartridge_and_secure), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.cartridge_press_new_cartridge_inserted), style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    CartridgeNotificationsPanel(
                        notifications = activeNotifications,
                        refreshing = notificationsRefreshing,
                        sendPumpCommands = sendPumpCommands,
                        refreshNotifications = refreshNotifications,
                    )
                    if (hasActiveNotifications) {
                        NotificationsBlockingWarning(stringResource(R.string.cartridge_clear_all_notice_before_continuing))
                    }
                }
                ChangeCartridgeStep.DETECTING -> {
                    Text(stringResource(R.string.cartridge_step_5_detecting), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.cartridge_detecting_insulin_in_cartridge), style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.cartridge_complete, (detectingCartridgeState?.percentComplete ?: 0 ?: "")),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                ChangeCartridgeStep.DONE -> {
                    Text(stringResource(R.string.cartridge_change_complete), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.cartridge_can_now_fill_tubing_and_cannula), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        actions = {
            when (step) {
                ChangeCartridgeStep.SUSPEND -> {
                    PrimaryActionButton(
                        text = stringResource(R.string.cartridge_suspend_insulin_delivery),
                        loading = loadingSuspend,
                        onClick = { showSuspendConfirm = true },
                    )
                }
                ChangeCartridgeStep.ENTER_MODE -> {
                    PrimaryActionButton(
                        text = stringResource(R.string.cartridge_start_cartridge_change),
                        loading = loadingEnterMode,
                        enabled = !hasActiveNotifications,
                        onClick = {
                            loadingEnterMode = true
                            onEnter()
                        },
                    )
                }
                ChangeCartridgeStep.WAITING_FOR_READY -> {
                    PrimaryActionButton(
                        text = stringResource(R.string.cartridge_waiting_for_pump),
                        enabled = false,
                        onClick = {},
                    )
                }
                ChangeCartridgeStep.PHYSICAL_CHANGE -> {
                    PrimaryActionButton(
                        text = stringResource(R.string.cartridge_new_cartridge_inserted),
                        enabled = !hasActiveNotifications,
                        onClick = onExit,
                    )
                }
                ChangeCartridgeStep.DETECTING -> {
                    PrimaryActionButton(
                        text = stringResource(R.string.cartridge_detecting),
                        enabled = false,
                        onClick = {},
                    )
                }
                ChangeCartridgeStep.DONE -> {
                    PrimaryActionButton(stringResource(R.string.cartridge_done), onClick = onDone)
                }
            }
        }
    )

    if (showSuspendConfirm) {
        AlertDialog(
            onDismissRequest = { showSuspendConfirm = false },
            title = { Text(stringResource(R.string.cartridge_suspend_insulin_delivery_query)) },
            text = { Text(stringResource(R.string.cartridge_stop_delivery_until_resume)) },
            dismissButton = {
                TextButton(onClick = { showSuspendConfirm = false }) {
                    Text(stringResource(R.string.cartridge_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSuspendConfirm = false
                    loadingSuspend = true
                    onSuspend()
                }) {
                    Text(stringResource(R.string.cartridge_suspend))
                }
            },
        )
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text(stringResource(R.string.cartridge_cancel_cartridge_change_query)) },
            text = { Text(stringResource(R.string.cartridge_change_is_not_complete)) },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text(stringResource(R.string.cartridge_no_continue))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirm = false
                    onCancelInProgress()
                    onDismiss()
                }) {
                    Text(stringResource(R.string.cartridge_yes_cancel))
                }
            },
        )
    }
}

private enum class ChangeCartridgeStep(val stepNumber: Int) {
    SUSPEND(1),
    ENTER_MODE(2),
    WAITING_FOR_READY(3),
    PHYSICAL_CHANGE(4),
    DETECTING(5),
    DONE(6),
}

private fun BasalStatus?.isSuspendedForCartridgeWorkflow(): Boolean {
    return this == BasalStatus.PUMP_SUSPENDED || this == BasalStatus.BASALIQ_SUSPENDED
}

@Preview(showBackground = true)
@Composable
fun ChangeCartridgeWorkflowScreenPreview() {
    ControlX2Theme {
        Surface(color = Color.White) {
            ChangeCartridgeWorkflowScreen(
                basalStatus = BasalStatus.PUMP_SUSPENDED,
                inChangeCartridgeMode = false,
                enterChangeCartridgeState = null,
                detectingCartridgeState = null,
                activeNotifications = emptyList(),
                notificationsRefreshing = false,
                sendPumpCommands = { _, _ -> },
                refreshNotifications = { Job() },
                onDismiss = {},
                onSuspend = {},
                onEnter = {},
                onExit = {},
                onDone = {},
                onCancelInProgress = {},
            )
        }
    }
}
