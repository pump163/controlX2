package com.pumppan.controlx2.bolus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.R
import com.pumppan.controlx2.bolus.preset.BolusPreset
import com.pumppan.controlx2.bolus.preset.BolusPresetSpec
import com.pumppan.controlx2.bolus.state.BolusFlowState
import com.pumppan.controlx2.bolus.state.BolusForm
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.BolusShape

/**
 * 编辑一条预设：改名 + 直接改**绝对值**（立即段 / 延后段 / 时长分钟）。
 * 预设只存绝对值，百分比仅作派生显示，不参与入库；不改任何输注状态。
 *
 * 复用大剂量输入组件，但传 `respectModelCapabilities = false`：预设不预设泵型号，
 * X2 上也能编辑方波/双波/百分比条目。拦截只在「应用」与「发送」两处。
 */
@Composable
fun BolusPresetEditDialog(
    preset: BolusPreset,
    limits: BolusLimits,
    errorText: String?,
    onDismiss: () -> Unit,
    onSave: (String, BolusPresetSpec) -> Unit,
) {
    var name by remember(preset.id) { mutableStateOf(preset.name) }
    var state by remember(preset.id) {
        mutableStateOf(
            BolusFlowState(
                shape = if (preset.extendedEnabled) BolusShape.DUAL else BolusShape.NORMAL,
                nowMilliunits = preset.immediateMilliunits,
                laterMilliunits = if (preset.extendedEnabled) preset.laterMilliunits else 0,
                extendedSeconds = if (preset.extendedEnabled) preset.durationMinutes * 60 else 0,
                percent = BolusForm.percentOf(
                    preset.immediateMilliunits,
                    preset.immediateMilliunits + preset.laterMilliunits,
                ),
            ),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bolus_v2_preset_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PresetNameField(name = name, onNameChange = { name = it })
                Spacer(Modifier.height(8.dp))
                BolusShapePanel(
                    state = state,
                    errors = emptyList(),
                    ciqEnabled = null,
                    limits = limits,
                    ciqVersion = null,
                    onChange = { state = it },
                    respectModelCapabilities = false,
                )
                if (errorText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val extended = state.shape != BolusShape.NORMAL
                    onSave(
                        name,
                        BolusPresetSpec(
                            immediateMilliunits = state.nowMilliunits.coerceAtLeast(0),
                            laterMilliunits = if (extended) state.laterMilliunits.coerceAtLeast(0) else 0,
                            durationMinutes = if (extended) state.extendedSeconds / 60 else 0,
                        ),
                    )
                }
            ) {
                Text(stringResource(R.string.setup_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dashboard_cancel))
            }
        },
    )
}

@Composable
private fun PresetNameField(name: String, onNameChange: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.bolus_v2_preset_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
