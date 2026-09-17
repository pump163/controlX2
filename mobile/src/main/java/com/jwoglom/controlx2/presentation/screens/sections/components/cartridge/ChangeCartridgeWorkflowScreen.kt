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
        title = "更换储药器",
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
                    Text("重要提示", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "更换储药器需要先暂停胰岛素输注。",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                ChangeCartridgeStep.ENTER_MODE -> {
                    Text("第 2 步：准备胰岛素泵", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("继续前请清除所有活动的泵通知。", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    CartridgeNotificationsPanel(
                        notifications = activeNotifications,
                        refreshing = notificationsRefreshing,
                        sendPumpCommands = sendPumpCommands,
                        refreshNotifications = refreshNotifications,
                    )
                    if (hasActiveNotifications) {
                        NotificationsBlockingWarning("开始更换储药器前请清除所有通知。")
                    }
                }
                ChangeCartridgeStep.WAITING_FOR_READY -> {
                    Text("第 3 步：等待胰岛素泵", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("胰岛素泵正在准备更换储药器。", style = MaterialTheme.typography.bodyLarge)
                }
                ChangeCartridgeStep.PHYSICAL_CHANGE -> {
                    Text("第 4 步：更换储药器", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("1. 取出旧储药器。", style = MaterialTheme.typography.bodyLarge)
                    Text("2. 装入新储药器并固定。", style = MaterialTheme.typography.bodyLarge)
                    Text("3. 完成后点击'已插入新储药器'。", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    CartridgeNotificationsPanel(
                        notifications = activeNotifications,
                        refreshing = notificationsRefreshing,
                        sendPumpCommands = sendPumpCommands,
                        refreshNotifications = refreshNotifications,
                    )
                    if (hasActiveNotifications) {
                        NotificationsBlockingWarning("继续前请清除所有通知。")
                    }
                }
                ChangeCartridgeStep.DETECTING -> {
                    Text("第 5 步：检测中", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("正在检测储药器中的胰岛素...", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${detectingCartridgeState?.percentComplete ?: 0}% 已完成",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                ChangeCartridgeStep.DONE -> {
                    Text("储药器更换完成！", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("现在可以充盈导管和充盈插管。", style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        actions = {
            when (step) {
                ChangeCartridgeStep.SUSPEND -> {
                    PrimaryActionButton(
                        text = "暂停胰岛素输注",
                        loading = loadingSuspend,
                        onClick = { showSuspendConfirm = true },
                    )
                }
                ChangeCartridgeStep.ENTER_MODE -> {
                    PrimaryActionButton(
                        text = "开始更换储药器",
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
                        text = "正在等待胰岛素泵...",
                        enabled = false,
                        onClick = {},
                    )
                }
                ChangeCartridgeStep.PHYSICAL_CHANGE -> {
                    PrimaryActionButton(
                        text = "已插入新储药器",
                        enabled = !hasActiveNotifications,
                        onClick = onExit,
                    )
                }
                ChangeCartridgeStep.DETECTING -> {
                    PrimaryActionButton(
                        text = "检测中...",
                        enabled = false,
                        onClick = {},
                    )
                }
                ChangeCartridgeStep.DONE -> {
                    PrimaryActionButton("完成", onClick = onDone)
                }
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
            title = { Text("取消更换储药器？") },
            text = { Text("储药器更换未完成。应用将尝试退出更换模式。") },
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
