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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme
import com.jwoglom.controlx2.shared.enums.GlucoseUnit
import com.jwoglom.controlx2.shared.util.GlucoseConverter
import kotlin.math.roundToInt

@Composable
fun AddProfileDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Int, Int, Int, Int, Int, Boolean) -> Unit
) {
    val dataStore = LocalDataStore.current
    val glucoseUnit by dataStore.glucoseUnitPreference.observeAsState(GlucoseUnit.MGDL)
    val unitAbbrev = glucoseUnit.abbreviation

    var profileName by remember { mutableStateOf("") }
    var carbRatio by remember { mutableStateOf("10") }
    var basalRate by remember { mutableStateOf("1.0") }
    var targetBG by remember { mutableStateOf(when (glucoseUnit) {
        GlucoseUnit.MGDL -> "110"
        GlucoseUnit.MMOL -> "6.1"
    }) }
    var isf by remember { mutableStateOf(when (glucoseUnit) {
        GlucoseUnit.MGDL -> "50"
        GlucoseUnit.MMOL -> "2.8"
    }) }
    var insulinDuration by remember { mutableStateOf("240") }
    var carbEntryEnabled by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("添加新配置文件")
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = { Text("配置文件名称") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Text(
                        "第一个时段的默认设置",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                item {
                    OutlinedTextField(
                        value = carbRatio,
                        onValueChange = { carbRatio = it },
                        label = { Text("碳水化合物比（g/u）") },
                        supportingText = { Text("示例：10 = 1:10 比例") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = basalRate,
                        onValueChange = { basalRate = it },
                        label = { Text("基础率（u/hr）") },
                        supportingText = { Text("示例：1.0") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = targetBG,
                        onValueChange = { targetBG = it },
                        label = { Text("目标血糖（$unitAbbrev）") },
                        supportingText = { Text(when (glucoseUnit) {
                            GlucoseUnit.MGDL -> "示例：110"
                            GlucoseUnit.MMOL -> "示例：6.1"
                        }) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = isf,
                        onValueChange = { isf = it },
                        label = { Text("ISF（$unitAbbrev/u）") },
                        supportingText = {
                            Text(when (glucoseUnit) {
                                GlucoseUnit.MGDL -> "示例：50 = 1u:50 mg/dL"
                                GlucoseUnit.MMOL -> "示例：2.8 = 1u:2.8 mmol/L"
                            })
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = insulinDuration,
                        onValueChange = { insulinDuration = it },
                        label = { Text("胰岛素持续时间（分钟）") },
                        supportingText = { Text("示例：240 = 4 小时") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val carbRatioInt = (carbRatio.toFloatOrNull() ?: 10f) * 1000
                    val basalRateMilliunits = ((basalRate.toFloatOrNull() ?: 1.0f) * 1000).toInt()
                    // Convert glucose values from display unit to mg/dL for pump
                    val targetBGMgdl = when (glucoseUnit) {
                        GlucoseUnit.MGDL -> targetBG.toIntOrNull() ?: 110
                        GlucoseUnit.MMOL -> GlucoseConverter.convert(
                            targetBG.toDoubleOrNull() ?: 6.1,
                            GlucoseUnit.MMOL, GlucoseUnit.MGDL
                        ).roundToInt()
                    }
                    val isfMgdl = when (glucoseUnit) {
                        GlucoseUnit.MGDL -> isf.toIntOrNull() ?: 50
                        GlucoseUnit.MMOL -> GlucoseConverter.convert(
                            isf.toDoubleOrNull() ?: 2.8,
                            GlucoseUnit.MMOL, GlucoseUnit.MGDL
                        ).roundToInt()
                    }
                    val insulinDurationInt = insulinDuration.toIntOrNull() ?: 240

                    onConfirm(
                        profileName,
                        carbRatioInt.toInt(),
                        basalRateMilliunits,
                        targetBGMgdl,
                        isfMgdl,
                        insulinDurationInt,
                        carbEntryEnabled
                    )
                },
                enabled = profileName.isNotBlank()
            ) {
                Text("创建")
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

@Preview(showBackground = true, name = "Add Profile Dialog")
@Composable
internal fun AddProfileDialogPreview() {
    ControlX2Theme() {
        AddProfileDialog(
            onDismiss = {},
            onConfirm = { _, _, _, _, _, _, _ -> }
        )
    }
}
