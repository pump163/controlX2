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
import androidx.compose.runtime.livedata.observeAsState
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
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme
import com.jwoglom.controlx2.shared.enums.GlucoseUnit
import com.jwoglom.controlx2.shared.util.GlucoseConverter
import kotlin.math.roundToInt
import com.jwoglom.pumpx2.pump.messages.builders.IDPManager
import com.jwoglom.pumpx2.pump.messages.models.InsulinUnit
import com.jwoglom.pumpx2.pump.messages.models.MinsTime
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.IDPSegmentResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.IDPSettingsResponse
import timber.log.Timber

@Composable
fun EditSegmentDialog(
    profile: IDPManager.Profile,
    segment: IDPSegmentResponse,
    segmentIndex: Int,
    onDismiss: () -> Unit,
    onConfirm: (MinsTime, Float, Long, Int, Int) -> Unit
) {
    val dataStore = LocalDataStore.current
    val glucoseUnit by dataStore.glucoseUnitPreference.observeAsState(GlucoseUnit.MGDL)

    val totalMinutes = segment.profileStartTime
    val initialHour = totalMinutes / 60
    val initialMinute = totalMinutes % 60
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false
    )
    var basalRate by remember { mutableStateOf(InsulinUnit.from1000To1(segment.profileBasalRate.toLong()).toString()) }
    var carbRatio by remember { mutableStateOf(segment.profileCarbRatio.toString()) }
    var targetBG by remember { mutableStateOf(
        GlucoseConverter.format(segment.profileTargetBG, glucoseUnit)
    ) }
    var isf by remember { mutableStateOf(
        GlucoseConverter.format(segment.profileISF, glucoseUnit)
    ) }
    var errorMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("编辑配置文件时段")
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 0.dp),
            ) {
                item {
                    Text("正在编辑 ${profile.idpSettingsResponse.name}（#${profile.idpId}）中的第 ${segmentIndex} 个时段", fontSize = 14.sp)
                }
                item {
                    Text(
                        "开始时间",
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
                        label = { Text("基础率（u/hr）") },
                        placeholder = { Text("例如：1.0") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = carbRatio,
                        onValueChange = { carbRatio = it },
                        label = { Text("碳水化合物比（g/u）") },
                        supportingText = { Text("示例：10 = 1:10 比例") },
                        placeholder = { Text("例如：10g") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = targetBG,
                        onValueChange = { targetBG = it },
                        label = { Text("目标血糖（${glucoseUnit.abbreviation}）") },
                        placeholder = { Text(when (glucoseUnit) {
                            GlucoseUnit.MGDL -> "例如：110"
                            GlucoseUnit.MMOL -> "例如：6.1"
                        }) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = isf,
                        onValueChange = { isf = it },
                        label = { Text("ISF（${glucoseUnit.abbreviation}/u）") },
                        supportingText = {
                            Text(when (glucoseUnit) {
                                GlucoseUnit.MGDL -> "示例：50 = 1u:50 mg/dL"
                                GlucoseUnit.MMOL -> "示例：2.8 = 1u:2.8 mmol/L"
                            })
                        },
                        placeholder = { Text(when (glucoseUnit) {
                            GlucoseUnit.MGDL -> "例如：50"
                            GlucoseUnit.MMOL -> "例如：2.8"
                        }) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
                        val newStartTime = MinsTime(hours, minutes)
                        val basalRateFloat = basalRate.toFloatOrNull() ?: 0f
                        val carbRatioLong = carbRatio.toLongOrNull() ?: 0L
                        // Convert glucose values from display unit to mg/dL for pump
                        val targetBGMgdl = when (glucoseUnit) {
                            GlucoseUnit.MGDL -> targetBG.toIntOrNull() ?: 0
                            GlucoseUnit.MMOL -> GlucoseConverter.convert(
                                targetBG.toDoubleOrNull() ?: 0.0,
                                GlucoseUnit.MMOL, GlucoseUnit.MGDL
                            ).roundToInt()
                        }
                        val isfMgdl = when (glucoseUnit) {
                            GlucoseUnit.MGDL -> isf.toIntOrNull() ?: 0
                            GlucoseUnit.MMOL -> GlucoseConverter.convert(
                                isf.toDoubleOrNull() ?: 0.0,
                                GlucoseUnit.MMOL, GlucoseUnit.MGDL
                            ).roundToInt()
                        }

                        if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) {
                            errorMessage = "时间无效。小时必须为 0-23，分钟必须为 0-59。"
                            return@TextButton
                        }
                        if (basalRateFloat <= 0) {
                            errorMessage = "基础率必须大于 0"
                            return@TextButton
                        }
                        if (carbRatioLong <= 0) {
                            errorMessage = "碳水化合物比必须大于 0"
                            return@TextButton
                        }
                        if (targetBGMgdl <= 0) {
                            errorMessage = "目标血糖必须大于 0"
                            return@TextButton
                        }
                        if (isfMgdl <= 0) {
                            errorMessage = "ISF 必须大于 0"
                            return@TextButton
                        }

                        onConfirm(newStartTime, basalRateFloat, carbRatioLong, targetBGMgdl, isfMgdl)
                    } catch (e: Exception) {
                        errorMessage = "错误：${e.message}"
                        Timber.e(e, "Error updating segment")
                    }
                }
            ) {
                Text("更新时段")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("取消")
            }
        }
    )
}

@Preview(showBackground = true, name = "Edit Segment Dialog")
@Composable
internal fun EditSegmentDialogPreview() {
    ControlX2Theme() {
        // Create a mock profile and segment for preview
        val mockResponse = IDPSettingsResponse(
            1, // idpId
            "Morning Profile", // name
            1, // carbEntryEnabled
            240, // insulinDuration
            0, // maxBolusAmount
            false // bolusCalculatorEnabled
        )
        val mockProfile = IDPManager.Profile(mockResponse, false, 0)

        val mockSegment = IDPSegmentResponse(
            0, // profileId
            0, // segmentId
            480, // profileStartTime - 8:00 AM in minutes
            0, // profileEndTime
            1000L, // profileBasalRate - 1.0 u/hr in milliunits (Long)
            10, // profileCarbRatio
            50, // profileISF
            110 // profileTargetBG
        )

        EditSegmentDialog(
            profile = mockProfile,
            segment = mockSegment,
            segmentIndex = 0,
            onDismiss = {},
            onConfirm = { _, _, _, _, _ -> }
        )
    }
}
