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
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R

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
        title = stringResource(R.string.cartridge_fill_cannula),
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
                    Text(stringResource(R.string.cartridge_important), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.cartridge_fill_cannula_requires_suspend),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                FillCannulaStep.ENTER_AMOUNT -> {
                    Text(stringResource(R.string.cartridge_step_2_set_prime_amount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.cartridge_select_how_much_insulin_to_prime), style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    DecimalOutlinedText(
                        title = stringResource(R.string.cartridge_prime_amount_u),
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
                    Text(stringResource(R.string.fill_cannula_prime_amount, "%.1f".format(primeAmount)), style = MaterialTheme.typography.titleMedium)
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
                    Text(stringResource(R.string.cartridge_cannula_fill_complete), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.fill_cannula_primed, "%.1f".format(primeAmount)), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        actions = {
            when (step) {
                FillCannulaStep.SUSPEND ->
                    PrimaryActionButton(
                        text = stringResource(R.string.cartridge_suspend_insulin_delivery),
                        loading = loadingSuspend,
                        onClick = { showSuspendConfirm = true },
                    )
                FillCannulaStep.ENTER_AMOUNT ->
                    PrimaryActionButton(
                        text = stringResource(R.string.cartridge_fill_cannula_2),
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
                            Text(if (loadingResume) stringResource(R.string.cartridge_working) else stringResource(R.string.cartridge_resume_insulin_delivery))
                        }
                        Button(
                            onClick = onDone,
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) {
                            Text(stringResource(R.string.cartridge_done))
                        }
                    }
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

    if (showResumeConfirm) {
        AlertDialog(
            onDismissRequest = { showResumeConfirm = false },
            title = { Text(stringResource(R.string.cartridge_resume_insulin_delivery_query)) },
            text = { Text(stringResource(R.string.cartridge_will_restart_sched_basal_insulin)) },
            dismissButton = {
                TextButton(onClick = { showResumeConfirm = false }) {
                    Text(stringResource(R.string.cartridge_not_yet))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showResumeConfirm = false
                    loadingResume = true
                    onResume()
                }) {
                    Text(stringResource(R.string.cartridge_resume))
                }
            },
        )
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text(stringResource(R.string.cartridge_cancel_cannula_fill_query)) },
            text = { Text(stringResource(R.string.cartridge_cannula_fill_is_not_complete)) },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text(stringResource(R.string.cartridge_no_continue))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirm = false
                    onDismiss()
                }) {
                    Text(stringResource(R.string.cartridge_yes_cancel))
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
