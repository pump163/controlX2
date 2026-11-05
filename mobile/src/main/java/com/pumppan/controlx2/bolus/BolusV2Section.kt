package com.pumppan.controlx2.bolus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.R

/**
 * v2 大剂量分区：动作表 ↔ 四步输入页。整个分区不发任何泵命令。
 */
@Composable
fun BolusV2Section(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
) {
    var showFlow by rememberSaveable { mutableStateOf(false) }

    if (showFlow) {
        BolusFlowScreen(
            innerPadding = innerPadding,
            onExit = { showFlow = false },
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.dashboard_bolus),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            BolusActionMenu(
                onNewBolus = { showFlow = true },
                onBack = onBack,
            )
        }
    }
}
