package com.jwoglom.controlx2.presentation.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.AutoCenteringParams
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.ScalingLazyListState
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.dialog.Alert
import com.google.android.horologist.compose.navscaffold.scrollableColumn
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.presentation.ui.components.rememberRemoteTextInputLauncher
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.control.SetDexcomG7PairingCodeRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetG6TransmitterIdRequest
import com.jwoglom.pumpx2.pump.messages.request.control.StartDexcomG6SensorSessionRequest
import com.jwoglom.pumpx2.pump.messages.request.control.StopDexcomCGMSensorSessionRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CGMStatusRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watch CGM transmitter / sensor session management. Three primary actions
 * routed through `to-pump`-prefixed messages so HybridMessageBus handles
 * both PUMP_HOST (local BT) and CLIENT (forward to phone) modes uniformly:
 *
 *  - **Start G6 sensor session.** Two-step text entry: G6 transmitter ID
 *    (6 chars) → G6 sensor code (digits, or "0000" to attach to an
 *    in-progress sensor). On confirm, dispatches
 *    `SetG6TransmitterIdRequest` then waits 750 ms (mobile pattern, mirror
 *    of `mobile/.../CGMActions.kt:300-318`) before dispatching
 *    `StartDexcomG6SensorSessionRequest(sensorCode.toInt())` and finally
 *    a `CGMStatusRequest()` to refresh state.
 *  - **Pair G7 sensor.** Single text entry: 8-digit pairing code →
 *    `SetDexcomG7PairingCodeRequest(code.toInt())`. G7 pairs atomically;
 *    no separate "start sensor" step.
 *  - **Stop sensor session.** Single confirm `Alert` →
 *    `StopDexcomCGMSensorSessionRequest()` + `CGMStatusRequest()` refresh.
 *
 * Path reference written without the slash-star suffix on purpose; Kotlin
 * treats it as a nested comment opener inside a KDoc block (same trap
 * that bit WearHybridMessageBus.kt in commit 7db3dc9 and ProfileSwitchScreen.kt
 * in a9df6ac / 61a0262).
 *
 * Validation is intentionally minimal — non-empty, length check on G6 tx
 * ID, numeric-string check on the codes. The pump itself enforces the
 * authoritative format / hex / length rules; rejections come back as
 * pumpx2 error responses surfaced via the existing `cgmSessionState` flow.
 */
@Composable
fun CGMTransmitterScreen(
    scalingLazyListState: ScalingLazyListState,
    focusRequester: FocusRequester,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val ds = LocalDataStore.current
    val sessionState by ds.cgmSessionState.observeAsState()
    val transmitterStatus by ds.cgmTransmitterStatus.observeAsState()

    LaunchedEffect(Unit) {
        sendPumpCommands(SendType.BUST_CACHE, listOf(CGMStatusRequest()))
    }

    val refreshScope = rememberCoroutineScope()

    var pendingG6TxId by remember { mutableStateOf<String?>(null) }
    var pendingG6SensorCode by remember { mutableStateOf<Int?>(null) }
    var pendingG7Code by remember { mutableStateOf<Int?>(null) }
    var pendingG6Confirm by remember { mutableStateOf(false) }
    var pendingG7Confirm by remember { mutableStateOf(false) }
    var pendingStopConfirm by remember { mutableStateOf(false) }
    var pendingError by remember { mutableStateOf<String?>(null) }

    // Launchers are defined in dependency order so each can reference the
    // next from its onResult: G6 sensor-code launcher is built first because
    // the G6 transmitter-ID launcher chains into it on success. Each
    // launcher is a stable () -> Unit returned by rememberRemoteTextInputLauncher.

    val g6SensorCodeLauncher = rememberRemoteTextInputLauncher(
        label = "G6探头代码（数字或0000）",
    ) { result ->
        if (result.isNullOrBlank()) return@rememberRemoteTextInputLauncher
        val code = result.toIntOrNull()
        if (code == null) {
            pendingError = "探头代码必须为数字。"
            return@rememberRemoteTextInputLauncher
        }
        pendingG6SensorCode = code
        pendingG6Confirm = true
    }

    val g6TxIdLauncher = rememberRemoteTextInputLauncher(
        label = "G6发射器ID（6位字符）",
    ) { result ->
        if (result.isNullOrBlank()) return@rememberRemoteTextInputLauncher
        if (result.length != 6) {
            pendingError = "发射器ID必须为6位字符。"
            return@rememberRemoteTextInputLauncher
        }
        pendingG6TxId = result.uppercase()
        // Chain to sensor-code entry. Defined above so it's already in scope.
        g6SensorCodeLauncher()
    }

    val g7CodeLauncher = rememberRemoteTextInputLauncher(
        label = "G7配对码（8位数字）",
    ) { result ->
        if (result.isNullOrBlank()) return@rememberRemoteTextInputLauncher
        val code = result.toIntOrNull()
        if (code == null) {
            pendingError = "配对码必须为数字。"
            return@rememberRemoteTextInputLauncher
        }
        pendingG7Code = code
        pendingG7Confirm = true
    }

    // Early-return alerts — error first, then per-action confirms.
    if (pendingError != null) {
        InfoAlert(
            message = pendingError ?: "",
            onDismiss = { pendingError = null },
        )
        return
    }
    if (pendingG6Confirm) {
        StartG6ConfirmAlert(
            txId = pendingG6TxId ?: "",
            sensorCode = pendingG6SensorCode ?: 0,
            onCancel = {
                pendingG6Confirm = false
                pendingG6TxId = null
                pendingG6SensorCode = null
            },
            onConfirm = {
                val tx = pendingG6TxId ?: return@StartG6ConfirmAlert
                val sensor = pendingG6SensorCode ?: return@StartG6ConfirmAlert
                refreshScope.launch {
                    // Mirror mobile CGMActions.kt:300-323: dispatch SetG6
                    // first, sleep ~750 ms for the pump to apply it, then
                    // dispatch Start, then refresh CGMStatus.
                    sendPumpCommands(
                        SendType.BUST_CACHE,
                        listOf(SetG6TransmitterIdRequest(tx)),
                    )
                    delay(750)
                    sendPumpCommands(
                        SendType.BUST_CACHE,
                        listOf(StartDexcomG6SensorSessionRequest(sensor)),
                    )
                    delay(250)
                    sendPumpCommands(SendType.BUST_CACHE, listOf(CGMStatusRequest()))
                }
                pendingG6Confirm = false
                pendingG6TxId = null
                pendingG6SensorCode = null
            },
        )
        return
    }
    if (pendingG7Confirm) {
        PairG7ConfirmAlert(
            code = pendingG7Code ?: 0,
            onCancel = {
                pendingG7Confirm = false
                pendingG7Code = null
            },
            onConfirm = {
                val code = pendingG7Code ?: return@PairG7ConfirmAlert
                refreshScope.launch {
                    sendPumpCommands(
                        SendType.BUST_CACHE,
                        listOf(SetDexcomG7PairingCodeRequest(code)),
                    )
                    delay(250)
                    sendPumpCommands(SendType.BUST_CACHE, listOf(CGMStatusRequest()))
                }
                pendingG7Confirm = false
                pendingG7Code = null
            },
        )
        return
    }
    if (pendingStopConfirm) {
        StopSessionConfirmAlert(
            onCancel = { pendingStopConfirm = false },
            onConfirm = {
                refreshScope.launch {
                    sendPumpCommands(
                        SendType.BUST_CACHE,
                        listOf(StopDexcomCGMSensorSessionRequest()),
                    )
                    delay(250)
                    sendPumpCommands(SendType.BUST_CACHE, listOf(CGMStatusRequest()))
                }
                pendingStopConfirm = false
            },
        )
        return
    }

    val sessionLabel = sessionState ?: "未知"
    val transmitterLabel = transmitterStatus.takeUnless { it.isNullOrBlank() } ?: "—"

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scrollableColumn(focusRequester, scalingLazyListState)
            .padding(horizontal = 8.dp),
        state = scalingLazyListState,
        autoCentering = AutoCenteringParams(),
    ) {
        item {
            Text(
                text = "CGM",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
            )
        }
        item {
            Text(
                text = "会话：$sessionLabel  •  发射器：$transmitterLabel",
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        item {
            Chip(
                onClick = g6TxIdLauncher,
                label = { Text("启动G6探头", fontSize = 13.sp) },
                secondaryLabel = {
                    Text("发射器ID + 探头代码", fontSize = 10.sp)
                },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = g7CodeLauncher,
                label = { Text("配对G7探头", fontSize = 13.sp) },
                secondaryLabel = {
                    Text("8位配对码", fontSize = 10.sp)
                },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = { pendingStopConfirm = true },
                label = { Text("停止探头会话", fontSize = 13.sp) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StartG6ConfirmAlert(
    txId: String,
    sensorCode: Int,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Alert(
        title = {
            Text(
                text = "用TX $txId、代码$sensorCode启动G6探头？",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onBackground,
            )
        },
        negativeButton = {
            Button(onClick = onCancel, colors = ButtonDefaults.secondaryButtonColors()) {
                Icon(Icons.Filled.Clear, contentDescription = "取消")
            }
        },
        positiveButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.primaryButtonColors()) {
                Icon(Icons.Filled.Check, contentDescription = "启动G6探头")
            }
        },
        icon = {
            Image(
                imageVector = Icons.Filled.Bluetooth,
                contentDescription = "G6",
                modifier = Modifier.size(24.dp),
            )
        },
    ) {}
}

@Composable
private fun PairG7ConfirmAlert(
    code: Int,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Alert(
        title = {
            Text(
                text = "用代码$code配对G7探头？",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onBackground,
            )
        },
        negativeButton = {
            Button(onClick = onCancel, colors = ButtonDefaults.secondaryButtonColors()) {
                Icon(Icons.Filled.Clear, contentDescription = "取消")
            }
        },
        positiveButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.primaryButtonColors()) {
                Icon(Icons.Filled.Check, contentDescription = "配对G7探头")
            }
        },
        icon = {
            Image(
                imageVector = Icons.Filled.Bluetooth,
                contentDescription = "G7",
                modifier = Modifier.size(24.dp),
            )
        },
    ) {}
}

@Composable
private fun StopSessionConfirmAlert(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Alert(
        title = {
            Text(
                text = "停止当前CGM探头会话？",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onBackground,
            )
        },
        negativeButton = {
            Button(onClick = onCancel, colors = ButtonDefaults.secondaryButtonColors()) {
                Icon(Icons.Filled.Clear, contentDescription = "取消")
            }
        },
        positiveButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.primaryButtonColors()) {
                Icon(Icons.Filled.Check, contentDescription = "停止探头会话")
            }
        },
        icon = {
            Image(
                imageVector = Icons.Filled.Stop,
                contentDescription = "停止",
                modifier = Modifier.size(24.dp),
            )
        },
    ) {}
}

@Composable
private fun InfoAlert(
    message: String,
    onDismiss: () -> Unit,
) {
    Alert(
        title = {
            Text(
                text = message,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onBackground,
            )
        },
        negativeButton = {},
        positiveButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.primaryButtonColors()) {
                Icon(Icons.Filled.Check, contentDescription = "确定")
            }
        },
        icon = {
            Image(
                imageVector = Icons.Filled.Clear,
                contentDescription = "信息",
                modifier = Modifier.size(24.dp),
            )
        },
    ) {}
}
