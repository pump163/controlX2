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
import com.pumppan.controlx2.bolus.state.BolusShape
import java.util.Locale

/** 第 3 步：校验与确认。「输注」按钮本轮不发送任何报文（待 S6）。 */
@Composable
fun BolusConfirmPanel(
    state: BolusFlowState,
    onDeliver: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier) {
        SummaryRow(stringResource(R.string.bolus_v2_confirm_mode), modeName(state.shape != BolusShape.NORMAL))
        SummaryRow(stringResource(R.string.bolus_v2_confirm_total), units(state.totalMilliunits))
        if (state.shape != BolusShape.NORMAL) {
            SummaryRow(stringResource(R.string.bolus_v2_confirm_immediate), units(state.nowMilliunits))
            SummaryRow(stringResource(R.string.bolus_v2_confirm_extended), units(state.laterMilliunits))
            SummaryRow(
                stringResource(R.string.bolus_v2_confirm_duration),
                stringResource(R.string.bolus_v2_duration_value, "%.1f".format(Locale.US, state.extendedSeconds / 3600.0)),
            )
            if (BolusForm.isImmediatePartUnverified(state.draft())) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.bolus_v2_confirm_now_small_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(onClick = onDeliver, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.bolus_v2_send))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.bolus_v2_send_unavailable_tag),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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

private fun units(milliunits: Int): String {
    val value = String.format(Locale.US, "%.3f", milliunits / 1000.0).trimEnd('0').trimEnd('.')
    return "${value.ifEmpty { "0" }} U"
}
