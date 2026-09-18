@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)

package com.jwoglom.controlx2.presentation.screens.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.presentation.components.HeaderLine
import com.jwoglom.controlx2.presentation.components.LoadSpinner
import com.jwoglom.controlx2.presentation.screens.LandingSection
import com.jwoglom.controlx2.presentation.screens.setUpPreviewState
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme
import com.jwoglom.controlx2.presentation.util.LifecycleStateObserver
import com.jwoglom.controlx2.shared.presentation.intervalOf
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.control.SetQuickBolusSettingsRequest
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

@Composable
fun QuickBolusSettingsActions(
    innerPadding: PaddingValues = PaddingValues(),
    navController: NavHostController? = null,
    sendMessage: (String, ByteArray) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    navigateBack: () -> Unit,
) {
    val context = LocalContext.current

    val refreshScope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(true) }

    fun fetchDataStoreFields(type: SendType) {
        sendPumpCommands(type, soundSettingsActionsCommands)
    }

    fun waitForLoaded() = refreshScope.launch {
        if (!Prefs(context).serviceEnabled()) return@launch
        var sinceLastFetchTime = 0
        while (true) {
            val nullFields = soundSettingsActionsFields.filter { field -> field.value == null }.toSet()
            if (nullFields.isEmpty()) {
                break
            }

            Timber.i("QuickBolusSettingsActions loading: remaining ${nullFields.size}: ${soundSettingsActionsFields.map { it.value }}")
            if (sinceLastFetchTime >= 2500) {
                Timber.i("QuickBolusSettingsActions loading re-fetching with cache")
                fetchDataStoreFields(SendType.CACHED)
                sinceLastFetchTime = 0
            }

            delay(250)
            sinceLastFetchTime += 250
        }
        Timber.i("QuickBolusSettingsActions loading done: ${soundSettingsActionsFields.map { it.value }}")
        refreshing = false
    }

    fun refresh() = refreshScope.launch {
        if (!Prefs(context).serviceEnabled()) return@launch
        Timber.i("reloading QuickBolusSettingsActions with force")
        refreshing = true

        soundSettingsActionsFields.forEach { field -> field.value = null }
        fetchDataStoreFields(SendType.BUST_CACHE)
    }

    val state = rememberPullRefreshState(refreshing, ::refresh)

    LifecycleStateObserver(lifecycleOwner = LocalLifecycleOwner.current, onStop = {
        refreshScope.cancel()
    }) {
        Timber.i("reloading QuickBolusSettingsActions from onStart lifecyclestate")
        fetchDataStoreFields(SendType.STANDARD)
    }

    LaunchedEffect(intervalOf(60)) {
        Timber.i("reloading QuickBolusSettingsActions from interval")
        fetchDataStoreFields(SendType.STANDARD)
    }

    LaunchedEffect(refreshing) {
        waitForLoaded()
    }

    // Quick Bolus Settings state
    var quickBolusDropdownExpanded by remember { mutableStateOf(false) }
    var quickBolusEnabled by remember { mutableStateOf(false) }
    var quickBolusIncrement by remember { mutableStateOf(SetQuickBolusSettingsRequest.QuickBolusIncrement.DISABLED) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullRefresh(state)
    ) {
        PullRefreshIndicator(
            refreshing, state,
            Modifier
                .align(Alignment.TopCenter)
                .zIndex(10f)
        )
        LazyColumn(
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 0.dp),
            content = {
                item {

                    HeaderLine("快捷大剂量设置")
                    Divider()
                }

                if (refreshing) {
                    item {
                        LoadSpinner("正在加载快捷大剂量设置...")
                    }
                }

                item {
                    Text(
                        "配置快捷大剂量模式和增量大小",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text("启用", modifier = Modifier.weight(1f))
                        Switch(
                            checked = quickBolusEnabled,
                            onCheckedChange = { quickBolusEnabled = it }
                        )
                    }
                }

                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text("增量：", modifier = Modifier.padding(top = 8.dp))
                        ExposedDropdownMenuBox(
                            expanded = quickBolusDropdownExpanded,
                            onExpandedChange = { quickBolusDropdownExpanded = !quickBolusDropdownExpanded }
                        ) {
                            OutlinedTextField(
                                value = quickBolusIncrement.name,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = quickBolusDropdownExpanded) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = quickBolusDropdownExpanded,
                                onDismissRequest = { quickBolusDropdownExpanded = false }
                            ) {
                                SetQuickBolusSettingsRequest.QuickBolusIncrement.values().forEach { increment ->
                                    DropdownMenuItem(
                                        text = { Text(increment.name) },
                                        onClick = {
                                            quickBolusIncrement = increment
                                            quickBolusEnabled = increment.enabled
                                            quickBolusDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    ListItem(
                        headlineContent = { Text("应用快捷大剂量设置") },
                        supportingContent = {
                            Text("发送 SetQuickBolusSettingsRequest 并使用这些值")
                        },
                        leadingContent = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        modifier = Modifier.clickable {
                            val message = SetQuickBolusSettingsRequest(quickBolusIncrement)
                            sendPumpCommands(SendType.STANDARD, listOf(message))
                            refreshScope.launch {
                                delay(500)
                                refresh()
                            }
                        }
                    )
                }

                item {
                    Divider()
                }

                item {
                    ListItem(
                        headlineContent = { Text("返回") },
                        leadingContent = { Icon(Icons.Filled.ArrowBack, contentDescription = null) },
                        modifier = Modifier.clickable(onClick = navigateBack),
                    )
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun QuickBolusSettingsActionsPreview() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            setUpPreviewState(LocalDataStore.current)
            QuickBolusSettingsActions(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
                navigateBack = {},
            )
        }
    }
}
