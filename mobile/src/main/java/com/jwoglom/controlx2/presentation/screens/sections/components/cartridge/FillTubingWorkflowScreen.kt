package com.jwoglom.controlx2.presentation.screens.sections.components.cartridge

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import com.jwoglom.pumpx2.pump.messages.response.controlStream.ExitFillTubingModeStateStreamResponse
import kotlinx.coroutines.Job
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R

@Composable
fun FillTubingWorkflowScreen(
    innerPadding: PaddingValues = PaddingValues(),
    basalStatus: BasalStatus?,
    inFillTubingMode: Boolean,
    fillTubingButtonDown: Boolean?,
    exitFillTubingState: ExitFillTubingModeStateStreamResponse?,
    exitRequested: Boolean,
    activeNotifications: List<Any>,
    notificationsRefreshing: Boolean,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    refreshNotifications: () -> Job,
    onDismiss: () -> Unit,
    onSuspend: () -> Unit,
    onBeginFillTubing: () -> Unit,
    onFinishFillTubing: () -> Unit,
    onDone: () -> Unit,
    onCancelInProgress: () -> Unit,
) {
    var showSuspendConfirm by rememberSaveable { mutableStateOf(false) }
    var showCancelConfirm by rememberSaveable { mutableStateOf(false) }
    var loadingSuspend by rememberSaveable { mutableStateOf(false) }
    var loadingEnterMode by rememberSaveable { mutableStateOf(false) }
    var loadingExitMode by rememberSaveable { mutableStateOf(false) }
    var hasDisplayedFlow by rememberSaveable { mutableStateOf(false) }

    val step = when {
        exitFillTubingState?.state == ExitFillTubingModeStateStreamResponse.ExitFillTubingModeState.TUBING_FILLED -> FillTubingStep.DONE
        exitRequested || (exitFillTubingState != null && !inFillTubingMode) -> FillTubingStep.EXITING
        inFillTubingMode -> FillTubingStep.FILLING
        basalStatus.isSuspendedForCartridgeWorkflow() -> FillTubingStep.ENTER_MODE
        else -> FillTubingStep.SUSPEND
    }

    val hasActiveNotifications = activeNotifications.isNotEmpty()
    val startedFlow = inFillTubingMode || exitRequested || exitFillTubingState != null

    LaunchedEffect(fillTubingButtonDown) {
        if (fillTubingButtonDown == true) {
            hasDisplayedFlow = true
        }
    }

    LaunchedEffect(step, basalStatus) {
        if (step != FillTubingStep.SUSPEND || basalStatus.isSuspendedForCartridgeWorkflow()) {
            loadingSuspend = false
        }
        if (step != FillTubingStep.ENTER_MODE && step != FillTubingStep.FILLING) {
            loadingEnterMode = false
        }
        if (step != FillTubingStep.EXITING) {
            loadingExitMode = false
        }
        if (step == FillTubingStep.SUSPEND) {
            hasDisplayedFlow = false
        }
    }

    CartridgeWorkflowScreen(
        title = stringResource(R.string.cartridge_fill_tubing),
        innerPadding = innerPadding,
        stepInfo = WizardStepInfo(step.stepNumber, 5),
        canCancel = true,
        onCancel = {
            if (startedFlow && step != FillTubingStep.DONE) {
                showCancelConfirm = true
            } else {
                onDismiss()
            }
        },
        body = {
            when (step) {
                FillTubingStep.SUSPEND -> {
                    Text(stringResource(R.string.cartridge_important), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.cartridge_fill_tubing_requires_suspend),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                FillTubingStep.ENTER_MODE -> {
                    Text(stringResource(R.string.cartridge_clear_active_notifications_first), style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    CartridgeNotificationsPanel(
                        notifications = activeNotifications,
                        refreshing = notificationsRefreshing,
                        sendPumpCommands = sendPumpCommands,
                        refreshNotifications = refreshNotifications,
                    )
                    if (hasActiveNotifications) {
                        NotificationsBlockingWarning(stringResource(R.string.cartridge_clear_notifications_before_tubing))
                    }
                }
                FillTubingStep.FILLING -> {
                    Text(stringResource(R.string.cartridge_press_and_hold_button_on), style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.cartridge_release_button_when_you_see_insulin), style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    when (fillTubingButtonDown) {
                        true -> {
                            Text(stringResource(R.string.cartridge_filling_keep_holding_pump_button), style = MaterialTheme.typography.bodyLarge)
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        false -> Text(stringResource(R.string.cartridge_see_insulin_at_tubing_end_query), style = MaterialTheme.typography.bodyLarge)
                        null -> Text(stringResource(R.string.cartridge_waiting_for_tubing_fill_input), style = MaterialTheme.typography.bodyLarge)
                    }
                    if (hasActiveNotifications) {
                        Spacer(modifier = Modifier.height(12.dp))
                        CartridgeNotificationsPanel(
                            notifications = activeNotifications,
                            refreshing = notificationsRefreshing,
                            sendPumpCommands = sendPumpCommands,
                            refreshNotifications = refreshNotifications,
                        )
                        NotificationsBlockingWarning(stringResource(R.string.cartridge_clear_notifications_complete_tubing))
                    }
                }
                FillTubingStep.EXITING -> {
                    Text(stringResource(R.string.cartridge_finalizing_tubing_fill_process), style = MaterialTheme.typography.bodyLarge)
                }
                FillTubingStep.DONE -> {
                    Text(stringResource(R.string.cartridge_fill_cannula_resume_insulin), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        actions = {
            when (step) {
                FillTubingStep.SUSPEND -> {
                    PrimaryActionButton(
                        text = stringResource(R.string.cartridge_suspend_insulin_delivery),
                        loading = loadingSuspend,
                        onClick = { showSuspendConfirm = true },
                    )
                }
                FillTubingStep.ENTER_MODE -> {
                    PrimaryActionButton(
                        text = stringResource(R.string.cartridge_start_tubing_fill),
                        loading = loadingEnterMode,
                        enabled = !hasActiveNotifications,
                        onClick = {
                            loadingEnterMode = true
                            onBeginFillTubing()
                        },
                    )
                }
                FillTubingStep.FILLING -> {
                    when {
                        fillTubingButtonDown == true -> {
                            PrimaryActionButton(
                                text = stringResource(R.string.cartridge_filling),
                                enabled = false,
                                onClick = {},
                            )
                        }
                        hasDisplayedFlow -> {
                            PrimaryActionButton(
                                text = stringResource(R.string.cartridge_complete_tubing_fill),
                                loading = loadingExitMode,
                                enabled = !hasActiveNotifications,
                                onClick = {
                                    loadingExitMode = true
                                    onFinishFillTubing()
                                },
                            )
                        }
                        else -> {
                            PrimaryActionButton(
                                text = stringResource(R.string.cartridge_hold_pump_button_to_fill),
                                enabled = false,
                                onClick = {},
                            )
                        }
                    }
                }
                FillTubingStep.EXITING -> {
                    PrimaryActionButton(
                        text = stringResource(R.string.cartridge_exiting_fill_mode),
                        enabled = false,
                        onClick = {},
                    )
                }
                FillTubingStep.DONE -> PrimaryActionButton(stringResource(R.string.cartridge_done), onClick = onDone)
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
            title = { Text(stringResource(R.string.cartridge_cancel_tubing_fill_query)) },
            text = { Text(stringResource(R.string.cartridge_tubing_fill_is_not_complete)) },
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

private enum class FillTubingStep(val stepNumber: Int) {
    SUSPEND(1),
    ENTER_MODE(2),
    FILLING(3),
    EXITING(4),
    DONE(5),
}

private fun BasalStatus?.isSuspendedForCartridgeWorkflow(): Boolean {
    return this == BasalStatus.PUMP_SUSPENDED || this == BasalStatus.BASALIQ_SUSPENDED
}

@Preview(showBackground = true)
@Composable
fun FillTubingWorkflowScreenPreview() {
    ControlX2Theme {
        Surface(color = Color.White) {
            FillTubingWorkflowScreen(
                basalStatus = BasalStatus.PUMP_SUSPENDED,
                inFillTubingMode = false,
                fillTubingButtonDown = null,
                exitFillTubingState = null,
                exitRequested = false,
                activeNotifications = emptyList(),
                notificationsRefreshing = false,
                sendPumpCommands = { _, _ -> },
                refreshNotifications = { Job() },
                onDismiss = {},
                onSuspend = {},
                onBeginFillTubing = {},
                onFinishFillTubing = {},
                onDone = {},
                onCancelInProgress = {},
            )
        }
    }
}
