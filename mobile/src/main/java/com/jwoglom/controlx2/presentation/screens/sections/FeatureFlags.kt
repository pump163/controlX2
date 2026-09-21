@file:OptIn(ExperimentalMaterial3Api::class)

package com.jwoglom.controlx2.presentation.screens.sections

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.jwoglom.controlx2.presentation.components.HeaderLine
import com.jwoglom.controlx2.shared.FeatureFlag

@Composable
fun FeatureFlags(
    innerPadding: PaddingValues = PaddingValues(),
    navController: NavHostController? = null,
    navigateBack: () -> Unit,
) {
    val context = LocalContext.current

    LazyColumn(
        contentPadding = innerPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 0.dp),
        content = {
            item {
                HeaderLine("功能开关")
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))

                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp)) {
                    Text("功能开关说明", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "BTHostSwitch：蓝牙主机角色切换 (Bluetooth Host Switch)，手表独立连泵用，手机端无需开启。关闭时手机连泵，手表通过手机中转；开启后手表可以直接当蓝牙主机连泵，手机当客户端。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            FeatureFlag.values().forEach { flag ->
                item {
                    var enabled by remember { mutableStateOf(FeatureFlag.enabled(context, flag)) }
                    ListItem(
                        headlineContent = { Text(flag.slug) },
                        trailingContent = {
                            Switch(
                                checked = enabled,
                                onCheckedChange = {
                                    enabled = it
                                    FeatureFlag.set(context, flag, it)
                                },
                            )
                        },
                        modifier = Modifier.clickable {
                            enabled = !enabled
                            FeatureFlag.set(context, flag, enabled)
                        },
                    )
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
                }
            }

            item {
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
                var fabEnabled by remember { mutableStateOf(com.jwoglom.controlx2.Prefs(context).fabEnabled()) }
                ListItem(
                    headlineContent = { Text("首页大剂量按钮") },
                    supportingContent = { Text("切换浮动大剂量按钮的显示。") },
                    trailingContent = {
                        Switch(
                            checked = fabEnabled,
                            onCheckedChange = {
                                fabEnabled = it
                                com.jwoglom.controlx2.Prefs(context).setFabEnabled(it)
                            }
                        )
                    },
                    modifier = Modifier.clickable {
                        fabEnabled = !fabEnabled
                        com.jwoglom.controlx2.Prefs(context).setFabEnabled(fabEnabled)
                    },
                )
            }

            item {
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
                ListItem(
                    headlineContent = { Text("返回") },
                    leadingContent = { Icon(Icons.Filled.ArrowBack, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = navigateBack),
                )
            }
        }
    )
}
