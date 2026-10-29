@file:OptIn(ExperimentalMaterial3Api::class)

package com.jwoglom.controlx2.presentation.screens.sections.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme
import com.jwoglom.pumpx2.pump.messages.builders.IDPManager
import com.jwoglom.pumpx2.pump.messages.models.MinsTime
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.IDPSettingsResponse
import timber.log.Timber
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.jwoglom.controlx2.R

@Composable
fun AddSegmentDialog(
    profile: IDPManager.Profile,
    onDismiss: () -> Unit,
    onConfirm: (MinsTime, Float, Long, Int, Int) -> Unit
) {
    val context = LocalContext.current
    val timePickerState = rememberTimePickerState(
        initialHour = 0,
        initialMinute = 0,
        is24Hour = false
    )
    var basalRate by remember { mutableStateOf("") }
    var carbRatio by remember { mutableStateOf("") }
    var targetBG by remember { mutableStateOf("") }
    var isf by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.profile_add_profile_segment))
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 0.dp),
            ) {
                item {
                    Text(stringResource(R.string.profile_adding_to, profile.idpSettingsResponse.name, profile.idpId), fontSize = 14.sp)
                }
                item {
                    Text(
                        stringResource(R.string.profile_start_time),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                item {
                    TimePicker(
                        state = timePickerState,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = basalRate,
                        onValueChange = { basalRate = it },
                        label = { Text(stringResource(R.string.profile_basal_rate_u_hr)) },
                        placeholder = { Text(stringResource(R.string.profile_e_g_1_0)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = carbRatio,
                        onValueChange = { carbRatio = it },
                        label = { Text(stringResource(R.string.profile_carb_ratio_g_u)) },
                        supportingText = { Text(stringResource(R.string.profile_example_10_1_10_ratio)) },
                        placeholder = { Text(stringResource(R.string.profile_e_g_10g)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = targetBG,
                        onValueChange = { targetBG = it },
                        label = { Text(stringResource(R.string.profile_target_bg_2)) },
                        placeholder = { Text(stringResource(R.string.profile_e_g_110)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = isf,
                        onValueChange = { isf = it },
                        label = { Text(stringResource(R.string.profile_isf_insulin_sensitivity_factor)) },
                        supportingText = { Text(stringResource(R.string.profile_example_50_1u_50_mgdl)) },
                        placeholder = { Text(stringResource(R.string.profile_e_g_50)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (errorMessage.isNotEmpty()) {
                    item {
                        Text(
                            errorMessage,
                            color = Color.Red,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    try {
                        val hours = timePickerState.hour
                        val minutes = timePickerState.minute
                        val startTime = MinsTime(hours, minutes)
                        val basalRateFloat = basalRate.toFloatOrNull() ?: 0f
                        val carbRatioLong = carbRatio.toLongOrNull() ?: 0L
                        val targetBGInt = targetBG.toIntOrNull() ?: 0
                        val isfInt = isf.toIntOrNull() ?: 0

                        if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) {
                            errorMessage = context.getString(R.string.profile_invalid_time_hours_0_23_mins)
                            return@TextButton
                        }
                        if (basalRateFloat <= 0) {
                            errorMessage = context.getString(R.string.profile_basal_rate_must_be_greater_than_0)
                            return@TextButton
                        }
                        if (carbRatioLong <= 0) {
                            errorMessage = context.getString(R.string.profile_carb_ratio_must_be_greater_than_0)
                            return@TextButton
                        }
                        if (targetBGInt <= 0) {
                            errorMessage = context.getString(R.string.profile_target_bg_must_be_greater_than_0)
                            return@TextButton
                        }
                        if (isfInt <= 0) {
                            errorMessage = context.getString(R.string.profile_isf_must_be_greater_than_0)
                            return@TextButton
                        }

                        onConfirm(startTime, basalRateFloat, carbRatioLong, targetBGInt, isfInt)
                    } catch (e: Exception) {
                        errorMessage = "Error: ${e.message}"
                        Timber.e(e, "Error creating segment")
                    }
                }
            ) {
                Text(stringResource(R.string.profile_add_segment))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.profile_cancel))
            }
        }
    )
}

@Preview(showBackground = true, name = "Add Segment Dialog")
@Composable
internal fun AddSegmentDialogPreview() {
    ControlX2Theme() {
        // Create a mock profile for preview
        val mockResponse = IDPSettingsResponse(
            1, // idpId
            "Morning Profile", // name
            1, // carbEntryEnabled
            240, // insulinDuration
            0, // maxBolusAmount
            false // bolusCalculatorEnabled
        )
        val mockProfile = IDPManager.Profile(mockResponse, false, 0)

        AddSegmentDialog(
            profile = mockProfile,
            onDismiss = {},
            onConfirm = { _, _, _, _, _ -> }
        )
    }
}
