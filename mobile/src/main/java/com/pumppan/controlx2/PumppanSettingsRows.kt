package com.pumppan.controlx2

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.R
import com.jwoglom.controlx2.shared.util.determinePumpModelFromName
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel

/** 设置页：语言切换（auto / 中文 / English），选完重建 Activity 立即生效。 */
@Composable
fun PumppanLanguageRow() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(PumppanPrefs.locale(context)) }
    PumppanChoiceRow(
        label = stringResource(R.string.pumppan_language),
        current = current,
        options = listOf(
            PumppanLocale.AUTO to stringResource(R.string.pumppan_language_auto),
            PumppanLocale.ZH to stringResource(R.string.pumppan_language_zh),
            PumppanLocale.EN to stringResource(R.string.pumppan_language_en),
        ),
        leadingIcon = Icons.Filled.Language,
    ) { mode ->
        current = mode
        PumppanPrefs.setLocale(context, mode)
        Toast.makeText(
            context,
            context.getString(R.string.pumppan_language_restart_hint),
            Toast.LENGTH_LONG
        ).show()
        context.findActivity()?.recreate()
    }
}

/** 调试页：强制泵型号，用来查看另一型号才显示的设置项。 */
@Composable
fun PumppanPumpModelRow() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(PumppanPrefs.pumpModel(context)) }
    PumppanChoiceRow(
        label = stringResource(R.string.pumppan_pump_model),
        current = current,
        options = listOf(
            PumppanPumpModel.AUTO to stringResource(R.string.pumppan_pump_model_auto),
            PumppanPumpModel.MOBI to stringResource(R.string.pumppan_pump_model_mobi),
            PumppanPumpModel.SLIM_X2 to stringResource(R.string.pumppan_pump_model_slimx2),
        ),
        leadingIcon = Icons.Filled.Build,
    ) { mode ->
        current = mode
        PumppanPumpModel.set(context, mode)
        Toast.makeText(
            context,
            context.getString(R.string.pumppan_pump_model_refresh_hint),
            Toast.LENGTH_LONG
        ).show()
    }
}

/** 强制型号与实际不符时的警告条：Mobi 专有操作会变成可点，风险自负。 */
@Composable
fun PumppanModelOverrideBanner() {
    val context = LocalContext.current
    val mode = remember { PumppanPrefs.pumpModel(context) }
    if (mode == PumppanPumpModel.AUTO) return

    val deviceName = LocalDataStore.current.setupDeviceName.observeAsState().value ?: ""
    val realModel = determinePumpModelFromName(deviceName)
    if (realModel == mode.model) return

    val displayed = stringResource(
        if (mode == PumppanPumpModel.MOBI) R.string.pumppan_pump_model_mobi
        else R.string.pumppan_pump_model_slimx2
    )
    val real = when (realModel) {
        KnownDeviceModel.MOBI -> stringResource(R.string.pumppan_pump_model_mobi)
        KnownDeviceModel.TSLIM_X2 -> stringResource(R.string.pumppan_pump_model_slimx2)
        else -> deviceName.ifBlank { "?" }
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.pumppan_model_mismatch_warning, displayed, real),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun <T> PumppanChoiceRow(
    label: String,
    current: T,
    options: List<Pair<T, String>>,
    leadingIcon: ImageVector? = null,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(options.firstOrNull { it.first == current }?.second ?: "") },
        leadingContent = leadingIcon?.let { icon ->
            { Icon(icon, contentDescription = null) }
        },
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column {
                    options.forEach { (value, text) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { open = false; onSelect(value) }
                                .padding(vertical = 8.dp),
                        ) {
                            RadioButton(
                                selected = value == current,
                                onClick = { open = false; onSelect(value) },
                            )
                            Text(text, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.actions_cancel))
                }
            },
        )
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
