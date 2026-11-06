package com.pumppan.controlx2.bolus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.R
import com.pumppan.controlx2.bolus.state.BolusFlowState
import com.pumppan.controlx2.bolus.state.BolusForm
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.BolusShape
/** 第 3 步：校验与确认。Mobi 延展未真机验证时显示实验提示；发送失败原因就地展示。 */
@Composable
fun BolusConfirmPanel(
    state: BolusFlowState,
    limits: BolusLimits = BolusLimits.Default,
    justSaved: Boolean = false,
    /** 该型号的延展能力未验证（Mobi）：显示「实验特性」说明。 */
    experimental: Boolean = false,
    /** 发送前二次校验 / 权限失败的文案；仅在确认页展示，不禁止按钮（按钮内仍会二次校验）。 */
    errorText: String? = null,
    /** -94 取权限进行中时置灰，避免重复发送。 */
    deliverEnabled: Boolean = true,
    onDeliver: () -> Unit,
    onSaveAsPreset: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier) {
        SummaryRow(stringResource(R.string.bolus_v2_confirm_mode), modeName(state.shape != BolusShape.NORMAL))
        SummaryRow(stringResource(R.string.bolus_v2_confirm_total), units(state.totalMilliunits, limits))
        if (state.shape != BolusShape.NORMAL) {
            SummaryRow(stringResource(R.string.bolus_v2_confirm_immediate), units(state.nowMilliunits, limits))
            SummaryRow(stringResource(R.string.bolus_v2_confirm_extended), units(state.laterMilliunits, limits))
            SummaryRow(
                stringResource(R.string.bolus_v2_confirm_duration),
                stringResource(R.string.bolus_v2_duration_value, state.extendedSeconds / 60),
            )
            if (BolusForm.isImmediatePartUnverified(state.draft(), limits)) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.bolus_v2_confirm_now_small_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (experimental) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.bolus_v2_extended_experimental),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(16.dp))

        if (errorText != null) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
        }
        Button(onClick = onDeliver, enabled = deliverEnabled, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.bolus_v2_send))
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(onClick = onSaveAsPreset, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.bolus_v2_preset_save_as))
        }
        if (justSaved) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.bolus_v2_preset_saved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.profile_back))
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun modeName(extended: Boolean): String = stringResource(
    if (extended) R.string.bolus_v2_mode_extended else R.string.bolus_v2_mode_standard,
)

private fun units(milliunits: Int, limits: BolusLimits): String =
    "${formatDose(milliunits, limits.bolusIncrementMilliunits)} U"
