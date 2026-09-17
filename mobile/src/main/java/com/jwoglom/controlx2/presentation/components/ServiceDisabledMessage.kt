package com.jwoglom.controlx2.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.shared.MessagePaths
import com.jwoglom.controlx2.Prefs
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.apache.commons.lang3.text.FormattableUtils.append

@Composable
fun ServiceDisabledMessage(
    sendMessage: (String, ByteArray) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val ds = LocalDataStore.current

    var enabled by remember { mutableStateOf(Prefs(context).serviceEnabled()) }
    var onlySnoopEnabled by remember { mutableStateOf(Prefs(context).onlySnoopBluetoothEnabled()) }

    val pumpConnected = ds.pumpConnected.observeAsState()
    LaunchedEffect (pumpConnected.value) {
        enabled = Prefs(context).serviceEnabled()
        onlySnoopEnabled = Prefs(context).onlySnoopBluetoothEnabled()
    }

    if (!enabled) {
        Card(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(8.dp).clickable {
                    Prefs(context).setServiceEnabled(true)
                    coroutineScope.launch {
                        delay(250)
                        // reload service, if running
                        sendMessage(MessagePaths.TO_SERVER_FORCE_RELOAD, "".toByteArray())
                        delay(250)
                        // reload main activity as fallback
                        sendMessage(MessagePaths.TO_SERVER_APP_RELOAD, "".toByteArray())
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).padding(8.dp)
                )
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("后台服务已禁用，ControlX2 无法连接胰岛素泵。")
                    }
                    append("点击此处重新启用服务。")
                })
            }
        }
    } else if (onlySnoopEnabled) {
        Card(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).padding(8.dp)
                )
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("OnlySnoopBluetooth 调试选项已启用，应用功能受限。")
                    }
                    append("选择'调试 > 禁用 Only Snoop Bluetooth'以禁用。")
                })
            }
        }
    }
}