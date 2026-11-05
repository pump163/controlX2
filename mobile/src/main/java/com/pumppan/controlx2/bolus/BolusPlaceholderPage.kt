package com.pumppan.controlx2.bolus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.R

/**
 * S9 过渡占位页：S4 落地后直接用新页面替换本文件即可，这里没有任何输入控件或泵命令。
 */
@Composable
fun BolusPlaceholderPage(innerPadding: PaddingValues, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(24.dp)
    ) {
        Text(stringResource(R.string.dashboard_bolus), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.bolus_v2_placeholder_body), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Button(onClick = onBack) { Text(stringResource(R.string.profile_back)) }
    }
}
