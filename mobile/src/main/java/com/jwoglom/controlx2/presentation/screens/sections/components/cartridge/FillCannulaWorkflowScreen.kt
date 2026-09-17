package com.jwoglom.controlx2.presentation.screens.sections.components.cartridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import com.jwoglom.controlx2.presentation.screens.sections.components.DecimalOutlinedText
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme
import com.jwoglom.controlx2.shared.enums.BasalStatus
import com.jwoglom.pumpx2.pump.messages.response.controlStream.FillCannulaStateStreamResponse
import kotlin.math.roundToInt
import java.util.Locale

@Composable
fun FillCannulaWorkflowScreen(
    innerPadding: PaddingValues = PaddingValues(),
    basalStatus: BasalStatus?,
    fillCannulaState: FillCannulaStateStreamResponse?,
    primeAmount: Double,
    onPrimeAmountChange: (Double) -> Unit,
    onDismiss: () -> Unit,
    onSuspend: () -> Unit,
    onResume: () -> Unit,
    onDone: () -> Unit,
    onSendFillRequest: () -> Unit,
) {
    var showSuspendConfirm by rememberSaveable { mutableStateOf(false) }
    var showResumeConfirm by rememberSaveable { mutableStateOf(false) }
    var showCancelConfirm by rememberSaveable { mutableStateOf(false) }
    var loadingSuspend by rememberSaveable { mutableStateOf(false) }
    var loadingFill by rememberSaveable { mutableStateOf(false) }
    var loadingResume by rememberSaveable { mutableStateOf(false) }
    var primeAmountText by rememberSaveable { mutableStateOf(String.format(Locale.US, "%.1f", primeAmount)) }

    val step = when {
        fillCannulaState?.state == FillCannulaStateStreamResponse.FillCannulaState.CANNULA_FILLED -> FillCannulaStep.DONE
        basalStatus.isSuspendedForCartridgeWorkflow() -> FillCannulaStep.ENTER_AMOUNT
        else -> FillCannulaStep.SUSPEND
    }

    LaunchedEffect(step, basalStatus) {
        if (step != FillCannulaStep.SUSPEND || basalStatus.isSuspendedForCartridgeWorkflow()) {
            loadingSuspend = false
        }
        if (step != FillCannulaStep.ENTER_AMOUNT) {
            loadingFill = false
        }
        if (step != FillCannulaStep.DONE || !basalStatus.isSuspendedForCartridgeWorkflow()) {
            loadingResume = false
        }
    }

    LaunchedEffect(primeAmount) {
        val normalized = String.format(Locale.US, "%.1f", primeAmount)
        if (primeAmountText != normalized) {
            primeAmountText = normalized
        }
    }

    CartridgeWorkflowScreen(
        title = "充盈插管",
        innerPadding = innerPadding,
        stepInfo = WizardStepInfo(step.stepNumber, 3),
        canCancel = true,
        onCancel = {
            if (step == FillCannulaStep.ENTER_AMOUNT) {
                showCancelConfirm = true
            } else {
                onDismiss()
            }
        },
        body = {
            when (step) {
                FillCannulaStep.SUSPEND -> {
                    Text("重要提示", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "充盈插管需要暂停胰岛素输注。请谨慎选择充注量。",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                FillCannulaStep.ENTER_AMOUNT -> {
                    Text("第 2 步：设置充注量", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("选择充盈插管所需的胰岛素量。", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    DecimalOutlinedText(
                        title = "充注量（U）",
                        value = primeAmountText,
                        onValueChange = { value ->
                            primeAmountText = value
                            val parsed = value.toDoubleOrNull()
                            if (parsed != null) {
                                val rounded = (parsed.coerceIn(0.1, 2.0) * 10.0).roundToInt() / 10.0
                                onPrimeAmountChange(rounded)
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("充注量：${"%.1f".format(primeAmount)}U", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = primeAmount.toFloat(),
                        onValueChange = {
                            val rounded = ((it * 10f).roundToInt() / 10f).coerceIn(0.1f, 2.0f)
                            onPrimeAmountChange(rounded.toDouble())
                            primeAmountText = String.format(Locale.US, "%.1f", rounded)
                        },
                        valueRange = 0.1f..2.0f,
                        steps = 18,
                    )
                }
                FillCannulaStep.DONE -> {
                    Text("插管充盈完成！", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("${"%.1f".format(primeAmount)}U 已充注。", style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        actions = {
            when (step) {
                FillCannulaStep.SUSPEND ->
                    PrimaryActionButton(
                        text = "暂停胰岛素输注",
                        loading = loadingSuspend,
                        onClick = { showSuspendConfirm = true },
                    )
                FillCannulaStep.ENTER_AMOUNT ->
                    PrimaryActionButton(
                        text = "充盈插管",
                        loading = loadingFill,
                        onClick = {
                            loadingFill = true
                            onSendFillRequest()
                        },
                    )
                FillCannulaStep.DONE -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { showResumeConfirm = true },
                            enabled = !loadingResume,
                            modifier = Modifier.weight(1.5f).height(56.dp),
                        ) {
                            Text(if (loadingResume) "处理中..." else "恢复胰岛素输注")
                        }
                        Button(
                            onClick = onDone,
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) {
                            Text("完成")
                        }
                    }
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

    if (showResumeConfirm) {
        AlertDialog(
            onDismissRequest = { showResumeConfirm = false },
            title = { Text("恢复胰岛素输注？") },
            text = { Text("这将恢复按计划的基础率胰岛素。") },
            dismissButton = {
                TextButton(onClick = { showResumeConfirm = false }) {
                    Text("暂不")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showResumeConfirm = false
                    loadingResume = true
                    onResume()
                }) {
                    Text("恢复")
                }
            },
        )
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("取消充盈插管？") },
            text = { Text("插管充盈未完成。") },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text("不，继续")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirm = false
                    onDismiss()
                }) {
                    Text("是，取消")
                }
            },
        )
    }
}

private enum class FillCannulaStep(val stepNumber: Int) {
    SUSPEND(1),
    ENTER_AMOUNT(2),
    DONE(3),
}

private fun BasalStatus?.isSuspendedForCartridgeWorkflow(): Boolean {
    return this == BasalStatus.PUMP_SUSPENDED || this == BasalStatus.BASALIQ_SUSPENDED
}

@Preview(showBackground = true)
@Composable
fun FillCannulaWorkflowScreenPreview() {
    ControlX2Theme {
        Surface(color = Color.White) {
            FillCannulaWorkflowScreen(
                basalStatus = BasalStatus.PUMP_SUSPENDED,
                fillCannulaState = null,
                primeAmount = 0.3,
                onPrimeAmountChange = {},
                onDismiss = {},
                onSuspend = {},
                onResume = {},
                onDone = {},
                onSendFillRequest = {},
            )
        }
    }
}
