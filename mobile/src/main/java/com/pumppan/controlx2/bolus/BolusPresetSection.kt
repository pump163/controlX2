package com.pumppan.controlx2.bolus

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.R
import com.pumppan.controlx2.bolus.preset.BolusPreset
import com.pumppan.controlx2.bolus.preset.BolusPresetMove
import com.pumppan.controlx2.bolus.state.BolusLimits

/**
 * 第 1 步的预设区：最多 12 条、双列紧凑格，全部条目一次渲染完（列表自身不滚动）。
 * 短按 = 选中（单选，再点取消）；长按或右侧菜单 = 修改 / 上移 / 下移。
 * 删除与应用由页面底部的三按钮触发。
 */
@Composable
fun BolusPresetSection(
    presets: List<BolusPreset>,
    limits: BolusLimits,
    selectedId: Long?,
    onSelect: (BolusPreset) -> Unit,
    onEdit: (BolusPreset) -> Unit,
    onMove: (BolusPreset, BolusPresetMove) -> Unit,
    /** 「预设」独立标签里标签栏已给出页名，不再重复渲染小标题。 */
    showHeader: Boolean = true,
) {
    if (showHeader) {
        Text(
            text = stringResource(R.string.bolus_v2_entry_preset),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
    }
    if (presets.isEmpty()) {
        Text(
            text = stringResource(R.string.bolus_v2_entry_preset_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    presets.chunked(PRESET_COLUMNS).forEachIndexed { rowIndex, row ->
        if (rowIndex > 0) Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEachIndexed { columnIndex, preset ->
                val index = rowIndex * PRESET_COLUMNS + columnIndex
                PresetCell(
                    index = index,
                    preset = preset,
                    limits = limits,
                    selected = preset.id == selectedId,
                    canMoveUp = index > 0,
                    canMoveDown = index < presets.lastIndex,
                    onSelect = onSelect,
                    onEdit = onEdit,
                    onMove = onMove,
                    modifier = Modifier.weight(1f),
                )
            }
            if (row.size < PRESET_COLUMNS) Spacer(Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetCell(
    index: Int,
    preset: BolusPreset,
    limits: BolusLimits,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: (BolusPreset) -> Unit,
    onEdit: (BolusPreset) -> Unit,
    onMove: (BolusPreset, BolusPresetMove) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val background = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val border = if (selected) {
        Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape)
    } else {
        Modifier
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(shape)
                .background(background)
                .then(border)
                .combinedClickable(
                    onClick = { onSelect(preset) },
                    onLongClick = { menuOpen = true },
                )
                .padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = (index + 1).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(18.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = presetMeta(preset, limits),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bolus_v2_preset_edit)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onEdit(preset)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bolus_v2_preset_move_up)) },
                leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                enabled = canMoveUp,
                onClick = {
                    menuOpen = false
                    onMove(preset, BolusPresetMove.UP)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bolus_v2_preset_move_down)) },
                leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                enabled = canMoveDown,
                onClick = {
                    menuOpen = false
                    onMove(preset, BolusPresetMove.DOWN)
                },
            )
        }
    }
}

/** 格内摘要：未设置数值的空条目直接标「未设置」；延展以绝对值为主、百分比只做次要显示。 */
@Composable
private fun presetMeta(preset: BolusPreset, limits: BolusLimits): String {
    if (!preset.isConfigured) return stringResource(R.string.bolus_v2_preset_unset)
    val increment = limits.bolusIncrementMilliunits
    if (!preset.extendedEnabled) return "${formatDose(preset.totalMilliunits, increment)} U"
    return stringResource(
        R.string.bolus_v2_preset_summary_split,
        formatDose(preset.immediateMilliunits, increment),
        formatDose(preset.laterMilliunits, increment),
        preset.percent,
        100 - preset.percent,
        preset.durationMinutes,
    )
}

private const val PRESET_COLUMNS = 2
