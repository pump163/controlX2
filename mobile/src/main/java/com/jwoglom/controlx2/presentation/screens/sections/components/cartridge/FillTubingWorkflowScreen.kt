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
        title = "充盈导管",
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
                    Text("重要提示", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "充盈导管需要暂停胰岛素输注。您将使用胰岛素泵按钮进行充盈。",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                FillTubingStep.ENTER_MODE -> {
                    Text("继续前请清除所有活动的泵通知。", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    CartridgeNotificationsPanel(
                        notifications = activeNotifications,
                        refreshing = notificationsRefreshing,
                        sendPumpCommands = sendPumpCommands,
                        refreshNotifications = refreshNotifications,
                    )
                    if (hasActiveNotifications) {
                        NotificationsBlockingWarning("开始充盈导管前请清除所有通知。")
                    }
                }
                FillTubingStep.FILLING -> {
                    Text("按住胰岛素泵上的按钮以充盈导管。", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("当看到导管末端有胰岛素时松开按钮。", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    when (fillTubingButtonDown) {
                        true -> {
                            Text("充盈中...继续按住泵按钮。", style = MaterialTheme.typography.bodyLarge)
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        false -> Text("泵按钮已松开。您是否看到导管末端有胰岛素？", style = MaterialTheme.typography.bodyLarge)
                        null -> Text("等待导管充盈输入...", style = MaterialTheme.typography.bodyLarge)
                    }
                    if (hasActiveNotifications) {
                        Spacer(modifier = Modifier.height(12.dp))
                        CartridgeNotificationsPanel(
                            notifications = activeNotifications,
                            refreshing = notificationsRefreshing,
                            sendPumpCommands = sendPumpCommands,
                            refreshNotifications = refreshNotifications,
                        )
                        NotificationsBlockingWarning("完成导管充盈前请清除所有通知。")
                    }
                }
                FillTubingStep.EXITING -> {
                    Text("正在完成导管充盈流程...", style = MaterialTheme.typography.bodyLarge)
                }
                FillTubingStep.DONE -> {
                    Text("现在可以充盈插管并恢复胰岛素输注。", style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        actions = {
            when (step) {
                FillTubingStep.SUSPEND -> {
                    PrimaryActionButton(
                        text = "暂停胰岛素输注",
                        loading = loadingSuspend,
                        onClick = { showSuspendConfirm = true },
                    )
                }
                FillTubingStep.ENTER_MODE -> {
                    PrimaryActionButton(
                        text = "开始充盈导管",
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
                                text = "充盈中...",
                                enabled = false,
                                onClick = {},
                            )
                        }
                        hasDisplayedFlow -> {
                            PrimaryActionButton(
                                text = "完成导管充盈",
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
                                text = "按住泵按钮以充盈",
                                enabled = false,
                                onClick = {},
                            )
                        }
                    }
                }
                FillTubingStep.EXITING -> {
                    PrimaryActionButton(
                        text = "正在退出充盈模式...",
                        enabled = false,
                        onClick = {},
                    )
                }
                FillTubingStep.DONE -> PrimaryActionButton("完成", onClick = onDone)
            }
        }
    )

    if (showSuspendConfirm) {
        AlertDialog(
            onDismissRequest = { showSuspendConfirm = false },
            title = { Text("暂停胰岛素输注？") },
            text = { Text("这将停止所有胰岛素输注，直到您恢复。") },
            dismissButton = {
                TextButton(onClick = { showSuspendConfirm = false }) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSuspendConfirm = false
                    loadingSuspend = true
                    onSuspend()
                }) {
                    Text("暂停")
                }
            },
        )
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("取消充盈导管？") },
            text = { Text("导管充盈未完成。应用将尝试退出充盈模式。") },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text("不，继续")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirm = false
                    onCancelInProgress()
                    onDismiss()
                }) {
                    Text("是，取消")
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
