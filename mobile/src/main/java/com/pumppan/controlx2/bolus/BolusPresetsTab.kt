package com.pumppan.controlx2.bolus

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.R
import com.pumppan.controlx2.bolus.preset.BolusPreset
import com.pumppan.controlx2.bolus.preset.BolusPresetDatabase
import com.pumppan.controlx2.bolus.preset.BolusPresetSaveResult
import com.pumppan.controlx2.bolus.preset.BolusPresetSpec
import com.pumppan.controlx2.bolus.preset.BolusPresetStore
import com.pumppan.controlx2.bolus.preset.toggleSelection
import com.pumppan.controlx2.bolus.state.BolusLimits
import com.pumppan.controlx2.bolus.state.canStartRemoteExtended
import kotlinx.coroutines.launch

/**
 * 「预设」标签：预设清单 + 底部固定三按钮（新建 / 删除 / 应用）。
 *
 * - 应用只交接草稿，不自己进确认页：由调用方切到「大剂量」标签并打开确认页
 *   （来源标签记「预设」，退出流程时切回本标签）。
 * - 底栏走 [BolusBottomBar]，键盘弹出时整条上移，三按钮始终可点。
 */
@Composable
fun BolusPresetsTab(
    limits: BolusLimits,
    onApply: (BolusPreset) -> Unit,
) {
    val context = LocalContext.current
    // 纯本地 Room 库（独立文件），不读写任何泵数据。
    val store = remember(limits) {
        // v1 → v2 迁移按当前型号的输注步进取整，所以建库时把增量传进去（仅首次打开用到）。
        BolusPresetStore(
            BolusPresetDatabase.getDatabase(context, limits.bolusIncrementMilliunits).bolusPresetDao()
        )
    }
    val scope = rememberCoroutineScope()
    var presets by remember { mutableStateOf<List<BolusPreset>>(emptyList()) }
    var editingPreset by remember { mutableStateOf<BolusPreset?>(null) }
    var presetError by remember { mutableStateOf<BolusPresetSaveResult?>(null) }
    // 单选（null = 未选中）；删除/应用可用性只看它。
    var selectedPresetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingPreset by remember { mutableStateOf<BolusPreset?>(null) }
    // 「应用」能力闸门：条目本身照常可编辑/可选中，只在用出去的那一刻拦。
    var blockedPreset by remember { mutableStateOf<BolusPreset?>(null) }
    LaunchedEffect(store) { presets = store.list() }

    val presetErrorText: String? = when (presetError) {
        null -> null
        is BolusPresetSaveResult.Saved -> null
        BolusPresetSaveResult.EmptyName -> stringResource(R.string.bolus_v2_preset_error_empty_name)
        BolusPresetSaveResult.DuplicateName -> stringResource(R.string.bolus_v2_preset_error_duplicate)
        BolusPresetSaveResult.LimitReached -> stringResource(R.string.bolus_v2_preset_error_limit)
        BolusPresetSaveResult.NotFound -> stringResource(R.string.bolus_v2_preset_error_missing)
    }

    val selectedPreset = presets.firstOrNull { it.id == selectedPresetId }
    val presetFull = presets.size >= BolusPresetStore.MAX_PRESETS

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 与「大剂量」标签一致：页面顶部始终能看到上次大剂量。
            LastBolusReminder(
                modifier = Modifier.padding(bottom = 8.dp),
            )
            BolusPresetSection(
                presets = presets,
                limits = limits,
                selectedId = selectedPresetId,
                onSelect = { preset -> selectedPresetId = toggleSelection(selectedPresetId, preset.id) },
                onEdit = { preset ->
                    presetError = null
                    editingPreset = preset
                },
                onMove = { preset, direction ->
                    scope.launch { presets = store.move(preset.id, direction) }
                },
                showHeader = false,
            )
        }

        BolusBottomBar {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        // 满额时按钮仍可点，只给提示（置灰但不隐藏原因）。
                        if (presetFull) {
                            Toast.makeText(context, R.string.bolus_v2_preset_full, Toast.LENGTH_SHORT).show()
                        } else {
                            scope.launch {
                                // 「新建」= 建空条目（数值为空），插到选中条正后方 / 末尾，
                                // 随即打开编辑对话框让用户填数值；取消对话框时保留空条目（显示「未设置」）。
                                val emptySpec = BolusPresetSpec(
                                    immediateMilliunits = 0,
                                    laterMilliunits = 0,
                                    durationMinutes = 0,
                                )
                                when (val result = store.saveNewAutoAfter(selectedPresetId, emptySpec)) {
                                    is BolusPresetSaveResult.Saved -> {
                                        presets = store.list()
                                        presetError = null
                                        editingPreset = result.preset
                                    }

                                    else -> Toast.makeText(
                                        context,
                                        R.string.bolus_v2_preset_full,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    },
                    colors = if (presetFull) {
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.bolus_v2_preset_new))
                }
                OutlinedButton(
                    onClick = { deletingPreset = selectedPreset },
                    enabled = selectedPreset != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.bolus_v2_preset_delete))
                }
                Button(
                    onClick = {
                        selectedPreset?.let { preset ->
                            if (!preset.isConfigured) {
                                // 空条目（新建后未填写）不允许直接进确认页。
                                Toast.makeText(
                                    context,
                                    R.string.bolus_v2_preset_unset_hint,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else if (preset.extendedEnabled && !limits.canStartRemoteExtended()) {
                                // 型号不支持远程发起延展：不置灰条目，只在「应用」时拦下并给两个出口。
                                blockedPreset = preset
                            } else {
                                // 应用：交出预填草稿（直接定位到确认页），由上层切标签并打开流程。
                                selectedPresetId = null
                                onApply(preset)
                            }
                        }
                    },
                    enabled = selectedPreset != null,
                    colors = if (selectedPreset != null && !selectedPreset.isConfigured) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.bolus_v2_preset_apply))
                }
            }
        }
    }

    editingPreset?.let { preset ->
        BolusPresetEditDialog(
            preset = preset,
            limits = limits,
            errorText = presetErrorText,
            onDismiss = {
                presetError = null
                editingPreset = null
            },
            onSave = { name, spec ->
                scope.launch {
                    when (val result = store.overwrite(preset.id, name, spec)) {
                        is BolusPresetSaveResult.Saved -> {
                            presets = store.list()
                            presetError = null
                            editingPreset = null
                        }

                        else -> presetError = result
                    }
                }
            },
        )
    }

    // 延展不可用的「应用」拦截：立即段 > 0 时才给「改为立即段」，纯方波只有取消。
    blockedPreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { blockedPreset = null },
            title = {
                Text(
                    stringResource(
                        R.string.bolus_v2_preset_extended_blocked_title,
                        limits.modelDisplayName ?: stringResource(R.string.bolus_v2_current_model),
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (preset.immediateMilliunits > 0) {
                            R.string.bolus_v2_preset_extended_blocked_text
                        } else {
                            R.string.bolus_v2_preset_extended_blocked_only_cancel
                        },
                    ),
                )
            },
            confirmButton = {
                if (preset.immediateMilliunits > 0) {
                    TextButton(
                        onClick = {
                            blockedPreset = null
                            selectedPresetId = null
                            onApply(preset.immediateOnly())
                        },
                    ) {
                        Text(stringResource(R.string.bolus_v2_preset_apply_immediate_only))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { blockedPreset = null }) {
                    Text(stringResource(R.string.profile_cancel))
                }
            },
        )
    }

    // 删除需二次确认；删后清空选中态（避免按钮仍指向已删除条目）。
    deletingPreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { deletingPreset = null },
            title = { Text(stringResource(R.string.bolus_v2_preset_delete_confirm_title)) },
            text = { Text(stringResource(R.string.bolus_v2_preset_delete_confirm_text, preset.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            store.delete(preset.id)
                            presets = store.list()
                            deletingPreset = null
                            selectedPresetId = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.bolus_v2_preset_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingPreset = null }) {
                    Text(stringResource(R.string.profile_cancel))
                }
            },
        )
    }
}
