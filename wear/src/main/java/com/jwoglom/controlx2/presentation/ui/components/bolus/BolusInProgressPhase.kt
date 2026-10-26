package com.jwoglom.controlx2.presentation.ui.components.bolus

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.presentation.DataStore
import androidx.compose.ui.res.stringResource

@Composable
fun BolusInProgressPhase(
    showInProgressDialog: Boolean,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onApproved: () -> Unit,
    onCancelled: () -> Unit,
    dataStore: DataStore,
) {
    val scrollState = rememberScalingLazyListState()
    Dialog(showDialog = showInProgressDialog, onDismissRequest = onDismiss, scrollState = scrollState) {
        val bolusFinalParameters = dataStore.bolusFinalParameters.observeAsState()
        val bolusInitiateResponse = dataStore.bolusInitiateResponse.observeAsState()
        val bolusCancelResponse = dataStore.bolusCancelResponse.observeAsState()
        val bolusMinNotifyThreshold = dataStore.bolusMinNotifyThreshold.observeAsState()
        val wearAutoApproveTimeout = dataStore.wearAutoApproveTimeout.observeAsState()

        var countdownSeconds by remember { mutableIntStateOf(wearAutoApproveTimeout.value ?: 0) }

        // Countdown timer for auto-approve
        LaunchedEffect(wearAutoApproveTimeout.value) {
            countdownSeconds = wearAutoApproveTimeout.value ?: 0
            if (countdownSeconds > 0) {
                while (countdownSeconds > 0) {
                    delay(1000)
                    countdownSeconds--
                }
            }
        }

        LaunchedEffect(bolusInitiateResponse.value) {
            if (bolusInitiateResponse.value != null) {
                onApproved()
            }
        }
        LaunchedEffect(bolusCancelResponse.value) {
            if (bolusCancelResponse.value != null) {
                onCancelled()
            }
        }

        Alert(
            title = {
                Text(
                    text = bolusFinalParameters.value?.let { stringResource(R.string.bolus_u_bolus_2, it.units) } ?: "",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onBackground
                )
            },
            negativeButton = {
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.bolus_cancel))
                }
            },
            positiveButton = {},
            scrollState = scrollState,
            icon = {
                Image(painterResource(R.drawable.bolus_icon), stringResource(R.string.bolus_icon), Modifier.size(24.dp))
            }
        ) {
            Text(
                text = when {
                    bolusInitiateResponse.value != null -> stringResource(R.string.bolus_request_recv_by_pump_waiting_for_resp)
                    bolusFinalParameters.value != null && bolusMinNotifyThreshold.value != null -> when {
                        bolusFinalParameters.value!!.units >= bolusMinNotifyThreshold.value!! ->
                            if (countdownSeconds > 0)
                                stringResource(R.string.bolus_will_begin_unless_canceled_on, countdownSeconds)
                            else if ((wearAutoApproveTimeout.value ?: 0) > 0)
                                stringResource(R.string.bolus_auto_approving_bolus)
                            else
                                stringResource(R.string.bolus_notice_was_sent_to_approve_request)
                        else -> stringResource(R.string.bolus_sending_request_to_pump)
                    }

                    else -> stringResource(R.string.bolus_sending_request_to_phone)
                },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackground
            )
        }
    }
}
