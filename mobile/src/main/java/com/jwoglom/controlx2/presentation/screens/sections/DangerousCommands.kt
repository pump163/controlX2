@file:OptIn(ExperimentalMaterial3Api::class)

package com.jwoglom.controlx2.presentation.screens.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.presentation.components.HeaderLine
import com.pumppan.controlx2.PumppanPumpModelRow
import com.pumppan.controlx2.ui.ActionRow

/**
 * Debug 页二级页「危险指令」：只承载两项高风险调试开关。
 *
 * 两项的 pref key、默认值与读写逻辑（[PumppanPumpModelRow] / `unsafe-raw-commands`）保持不变，
 * 本页只负责呈现与导航；原位置（Debug 页型号行、设置页 unsafe 行）已移除，避免两处出现。
 */
@Composable
fun DangerousCommands(
    innerPadding: PaddingValues = PaddingValues(),
    navigateBack: () -> Unit,
) {
    val context = LocalContext.current
    var unsafeRawCommands by remember {
        mutableStateOf(Prefs(context).unsafeRawCommandsEnabled())
    }

    LazyColumn(
        contentPadding = innerPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 0.dp),
        content = {
            item {
                HeaderLine(stringResource(R.string.settings_dangerous_commands))
                Divider()
            }

            // 泵型号覆盖：沿用原有选择对话框与持久化逻辑。
            item {
                PumppanPumpModelRow()
                Divider()
            }

            // 允许不安全的原始指令：沿用原有 pref 与开关联动。
            item {
                ActionRow(
                    title = context.getString(R.string.settings_allow_unsafe_raw_commands),
                    subtitle = context.getString(R.string.settings_allow_unsafe_raw_commands_subtitle),
                    leadingIcon = {
                        Icon(
                            if (unsafeRawCommands) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = context.getString(R.string.settings_unsafe_raw_commands_icon),
                        )
                    },
                    onClick = {
                        unsafeRawCommands = !unsafeRawCommands
                        Prefs(context).setUnsafeRawCommandsEnabled(unsafeRawCommands)
                    },
                )
                Divider()
            }

            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.app_back)) },
                    leadingContent = { Icon(Icons.Filled.ArrowBack, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = navigateBack),
                )
            }
        },
    )
}
