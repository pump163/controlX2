package com.pumppan.controlx2.bolus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.R
import com.pumppan.controlx2.bolus.state.BolusSource
import com.pumppan.controlx2.ui.NavigationRow

/** 第 1 步：入口选择。仅「直接输入」可用，其余为预留（置灰并写明原因）。 */
@Composable
fun BolusEntryPanel(onSelect: (BolusSource) -> Unit) {
    Column(Modifier) {
        NavigationRow(
            title = stringResource(R.string.bolus_v2_entry_manual),
            subtitle = stringResource(R.string.bolus_v2_entry_manual_sub),
            onClick = { onSelect(BolusSource.MANUAL) },
        )
        NavigationRow(
            title = stringResource(R.string.bolus_v2_entry_calc),
            subtitle = stringResource(R.string.bolus_v2_entry_calc_sub),
            onClick = {},
            readOnly = true,
        )
        NavigationRow(
            title = stringResource(R.string.bolus_v2_entry_preset),
            subtitle = stringResource(R.string.bolus_v2_entry_preset_sub),
            onClick = {},
            readOnly = true,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.bolus_v2_entry_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
