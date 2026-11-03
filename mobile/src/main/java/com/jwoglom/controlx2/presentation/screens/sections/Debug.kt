@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3Api::class)

package com.jwoglom.controlx2.presentation.screens.sections

import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Popup
import androidx.core.app.ShareCompat
import androidx.navigation.NavHostController
import com.google.android.material.slider.Slider
import com.google.common.base.Splitter
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.annotations.HistoryLogProps
import com.jwoglom.pumpx2.pump.messages.annotations.MessageProps
import com.jwoglom.pumpx2.pump.messages.request.control.BolusPermissionReleaseRequest
import com.jwoglom.pumpx2.pump.messages.request.control.CancelBolusRequest
import com.jwoglom.pumpx2.pump.messages.request.control.ChangeControlIQSettingsRequest
import com.jwoglom.pumpx2.pump.messages.request.control.ChangeTimeDateRequest
import com.jwoglom.pumpx2.pump.messages.request.control.CreateIDPRequest
import com.jwoglom.pumpx2.pump.messages.request.control.DeleteIDPRequest
import com.jwoglom.pumpx2.pump.messages.request.control.DismissNotificationRequest
import com.jwoglom.pumpx2.pump.messages.request.control.FillCannulaRequest
import com.jwoglom.pumpx2.pump.messages.request.control.InitiateBolusRequest
import com.jwoglom.pumpx2.pump.messages.request.control.RemoteBgEntryRequest
import com.jwoglom.pumpx2.pump.messages.request.control.RemoteCarbEntryRequest
import com.jwoglom.pumpx2.pump.messages.request.control.RenameIDPRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetActiveIDPRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetG6TransmitterIdRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetIDPSegmentRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetIDPSettingsRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetMaxBasalLimitRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetMaxBolusLimitRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetModesRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetPumpAlertSnoozeRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetPumpSoundsRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetQuickBolusSettingsRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetSleepScheduleRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetTempRateRequest
import com.jwoglom.pumpx2.pump.messages.request.control.StartDexcomG6SensorSessionRequest
import com.jwoglom.pumpx2.pump.messages.request.control.SetDexcomG7PairingCodeRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.BolusPermissionChangeReasonRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.HistoryLogRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.HistoryLogStatusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.IDPSegmentRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.IDPSettingsRequest
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.HistoryLogStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.historyLog.BolusDeliveryHistoryLog
import com.jwoglom.pumpx2.pump.messages.response.historyLog.HistoryLog
import com.jwoglom.pumpx2.pump.messages.util.MessageHelpers
import com.jwoglom.pumpx2.shared.JavaHelpers
import com.jwoglom.controlx2.LocalDataStore
import com.jwoglom.controlx2.Prefs
import com.jwoglom.controlx2.dataStore
import com.jwoglom.controlx2.db.historylog.HistoryLogDatabase
import com.jwoglom.controlx2.db.historylog.HistoryLogRepo
import com.jwoglom.controlx2.db.historylog.HistoryLogViewModel
import com.jwoglom.controlx2.presentation.components.HeaderLine
import com.jwoglom.controlx2.presentation.components.HistoryLogSyncProgressBar
import com.jwoglom.controlx2.presentation.util.SupportBundleSummary
import com.jwoglom.controlx2.presentation.theme.ControlX2Theme
import com.jwoglom.controlx2.presentation.util.formatLogLineCount
import com.jwoglom.controlx2.presentation.util.getSupportBundleSummary
import com.jwoglom.controlx2.presentation.util.sendSupportBundleEmail
import com.jwoglom.controlx2.presentation.util.shareSupportBundle
import com.jwoglom.controlx2.shared.FeatureFlags
import com.jwoglom.controlx2.shared.MessagePaths
import com.jwoglom.controlx2.shared.enums.GlucoseUnit
import com.jwoglom.controlx2.shared.util.SendType
import com.jwoglom.controlx2.shared.util.shortTimeAgo
import com.jwoglom.pumpx2.pump.PumpState
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.time.Instant
import java.util.stream.Collectors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import com.jwoglom.controlx2.R


/** 分隔线：与控制页分组横线同规格（1.5dp、colorScheme.outline、无额外留白）。 */
@Composable
private fun DebugDivider() {
    HorizontalDivider(
        thickness = 1.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * 弹窗遮罩容器：统一使用主题表面色与 onSurface 内容色。
 * Popup 内部没有 Material 层级，内容色会退化为默认黑色，暗色模式下就会「黑底黑字」，
 * 因此所有全屏查看器都必须套这一层。
 */
@Composable
private fun DebugPopupSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .wrapContentSize(Alignment.TopStart),
        ) {
            content()
        }
    }
}

@Composable
fun Debug(
    innerPadding: PaddingValues = PaddingValues(),
    navController: NavHostController? = null,
    sendMessage: (String, ByteArray) -> Unit,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    historyLogViewModel: HistoryLogViewModel? = null,
    navigateToFeatureFlags: () -> Unit = {},
    navigateBack: () -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager: ClipboardManager = LocalClipboardManager.current


    var showSendPumpMessageMenu by remember { mutableStateOf(false) }
    var showMessageCache by remember { mutableStateOf(false) }
    var showHistoryLogs by remember { mutableStateOf(false) }
    var showPumpState by remember { mutableStateOf(false) }
    var showSupportBundleDialog by remember { mutableStateOf(false) }
    var supportBundleSummary by remember { mutableStateOf<SupportBundleSummary?>(null) }

    val context = LocalContext.current
    val ds = LocalDataStore.current

    fun setClipboard(str: String) {
        clipboardManager.setText(AnnotatedString(str))
        Toast.makeText(context, context.getString(R.string.debug_saved_to_clipboard), Toast.LENGTH_SHORT).show()
    }

    fun shareTextContents(str: String, label: String, mimeType: String) {
        ShareCompat.IntentBuilder(context)
            .setType(mimeType)
            .setText(str)
            .setChooserTitle(label)
            .startChooser();
    }

    fun clearDebugLog(context: Context) {
        AlertDialog.Builder(context)
            .setMessage(context.getString(R.string.debug_are_sure_want_to_clear_saved_query))
            .setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.dismiss() }
            .setPositiveButton(context.getString(R.string.debug_delete)) { dialog, _ ->
                dialog.dismiss()
                val filePath = File(context.filesDir, "debugLog-MUA.txt")
                val deleted = filePath.delete()
                Toast.makeText(
                    context,
                    context.getString(
                        if (deleted) R.string.debug_debug_logs_cleared
                        else R.string.debug_no_debug_logs_to_clear
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    fun emptyDatabase(context: Context) {
        AlertDialog.Builder(context)
            .setMessage(context.getString(R.string.debug_are_sure_want_to_empty_database_query))
            .setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.dismiss() }
            .setPositiveButton(context.getString(R.string.debug_delete)) { dialog, _ ->
                dialog.dismiss()
                coroutineScope.launch {
                    val historyLogDb = HistoryLogDatabase.getDatabase(context)
                    val historyLogDao = historyLogDb.historyLogDao()
                    historyLogDao.deleteAll()
                    // 同步清理内存缓存（CommService 侧 + 界面侧），否则清空后仍显示旧条目
                    sendMessage(MessagePaths.TO_PUMP_DEBUG_CLEAR_HISTORYLOG_CACHE, "".toByteArray())
                    ds.historyLogCache.postValue(mutableMapOf<Long, HistoryLog>())
                    Toast.makeText(
                        context,
                        context.getString(R.string.debug_database_and_cache_cleared),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    LazyColumn(
        contentPadding = innerPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 0.dp),
        content = {
            item {
                HeaderLine(context.getString(R.string.debug_debug))
                DebugDivider()
            }
            if (Prefs(context).serviceEnabled()) {
                item {
                    ListItem(
                        headlineContent = { Text(context.getString(R.string.debug_disable_ctrl_x2_service)) },
                        supportingContent = { Text(context.getString(R.string.debug_stop_background_service)) },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = context.getString(R.string.debug_stop_icon),
                            )
                        },
                        modifier = Modifier.clickable {
                            Prefs(context).setServiceEnabled(false)
                            coroutineScope.launch {
                                delay(250)
                                sendMessage(MessagePaths.TO_SERVER_FORCE_RELOAD, "".toByteArray())
                            }
                        }
                    )
                    DebugDivider()
                }
            }
            // G2
            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.settings_send_support_bundle)) },
                    supportingContent = { Text(context.getString(R.string.settings_generate_debug_logs_zip)) },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Help,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable {
                        val summary = getSupportBundleSummary(context)
                        if (summary == null) {
                            Toast.makeText(context, context.getString(R.string.settings_no_debug_logs_are_available_to_share), Toast.LENGTH_SHORT).show()
                        } else {
                            supportBundleSummary = summary
                            showSupportBundleDialog = true
                        }
                    }
                )
                DebugDivider()
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentSize(Alignment.TopStart)
                ) {
                    ListItem(
                        headlineContent = { Text(context.getString(R.string.debug_send_pump_message)) },
                        supportingContent = { Text(context.getString(R.string.debug_displays_resp_mess_for_given_request)) },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Build,
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier.clickable {
                            showSendPumpMessageMenu = true
                        }
                    )
                    DropdownMenu(
                        expanded = showSendPumpMessageMenu,
                        onDismissRequest = { showSendPumpMessageMenu = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val requestMessages = MessageHelpers.getAllPumpRequestMessages()
                            .stream().filter { m: String ->
                                !m.startsWith("authentication.") &&
                                    !m.startsWith("historyLog.") &&
                                    !m.contains("FactoryResetRequest") &&
                                    !m.contains("FactoryResetBRequest") &&
                                    !m.contains("Nonexistent", ignoreCase = true)
                            }.collect(Collectors.toList())

                        requestMessages.forEach { message ->
                            DropdownMenuItem(
                                text = { Text("$message") },
                                onClick = {
                                    try {
                                        val className =
                                            MessageHelpers.REQUEST_PACKAGE + "." + message

                                        // Custom processing for arguments
                                        if (className == IDPSegmentRequest::class.java.name) {
                                            triggerIDPSegmentDialog(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == IDPSettingsRequest::class.java.name) {
                                            triggerIDPSettingsDialog(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == HistoryLogRequest::class.java.name) {
                                            triggerHistoryLogRequestDialog(
                                                context,
                                                sendPumpCommands
                                            )
                                            return@DropdownMenuItem
                                        } else if (className == InitiateBolusRequest::class.java.name) {
                                            triggerInitiateBolusRequestDialog(
                                                context,
                                                sendPumpCommands
                                            )
                                            return@DropdownMenuItem
                                        } else if (className == CancelBolusRequest::class.java.name) {
                                            triggerCancelBolusRequestDialog(
                                                context,
                                                sendPumpCommands
                                            )
                                            return@DropdownMenuItem
                                        } else if (className == BolusPermissionChangeReasonRequest::class.java.name) {
                                            triggerMessageWithBolusIdParameter(
                                                context,
                                                sendPumpCommands,
                                                BolusPermissionChangeReasonRequest::class.java
                                            )
                                            return@DropdownMenuItem
                                        } else if (className == BolusPermissionReleaseRequest::class.java.name) {
                                            triggerMessageWithBolusIdParameter(
                                                context,
                                                sendPumpCommands,
                                                BolusPermissionReleaseRequest::class.java
                                            )
                                            return@DropdownMenuItem
                                        } else if (className == RemoteBgEntryRequest::class.java.name) {
                                            triggerRemoteBgEntryRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == RemoteCarbEntryRequest::class.java.name) {
                                            triggerRemoteCarbEntryRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == SetModesRequest::class.java.name) {
                                            triggerSetModesRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == SetSleepScheduleRequest::class.java.name) {
                                            triggerSetSleepScheduleRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == SetTempRateRequest::class.java.name) {
                                            triggerSetTempRateRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == ChangeControlIQSettingsRequest::class.java.name) {
                                            triggerChangeControlIQSettingsRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == SetQuickBolusSettingsRequest::class.java.name) {
                                            triggerSetQuickBolusSettingsRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == DismissNotificationRequest::class.java.name) {
                                            triggerDismissNotificationRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == SetG6TransmitterIdRequest::class.java.name) {
                                            triggerSetG6TransmitterIdRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == StartDexcomG6SensorSessionRequest::class.java.name) {
                                            triggerStartDexcomG6SensorSessionRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == SetDexcomG7PairingCodeRequest::class.java.name) {
                                            triggerSetDexcomG7PairingCodeRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == FillCannulaRequest::class.java.name) {
                                            triggerFillCannulaRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == ChangeTimeDateRequest::class.java.name) {
                                            triggerChangeTimeDateRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == SetActiveIDPRequest::class.java.name) {
                                            triggerSetActiveIDPRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == CreateIDPRequest::class.java.name) {
                                            triggerCreateIDPRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == RenameIDPRequest::class.java.name) {
                                            triggerRenameIDPRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == DeleteIDPRequest::class.java.name) {
                                            triggerDeleteIDPRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == SetIDPSettingsRequest::class.java.name) {
                                            triggerSetIDPSettingsRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == SetIDPSegmentRequest::class.java.name) {
                                            triggerSetIDPSegmentRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == SetMaxBolusLimitRequest::class.java.name) {
                                            triggerSetMaxBolusLimitRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == SetMaxBasalLimitRequest::class.java.name) {
                                            triggerSetMaxBasalLimitRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == SetPumpSoundsRequest::class.java.name) {
                                            triggerSetPumpSoundsRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        } else if (className == SetPumpAlertSnoozeRequest::class.java.name) {
                                            triggerSetPumpAlertSnoozeRequestMessage(context, sendPumpCommands)
                                            return@DropdownMenuItem
                                        }
                                        val clazz = Class.forName(className)
                                        if (!Message::class.java.isAssignableFrom(clazz)) {
                                            throw IllegalArgumentException(context.getString(R.string.debug_class_is_not_mess_name, className))
                                        }
                                        Timber.i("Selected %s: %s", className, clazz)
                                        triggerGenericPumpMessageDialog(
                                            context,
                                            sendPumpCommands,
                                            clazz.asSubclass(Message::class.java)
                                        )
                                    } catch (e: ClassNotFoundException) {
                                        Timber.e(e)
                                        e.printStackTrace()
                                    } catch (e: IllegalArgumentException) {
                                        Timber.e(e)
                                        e.printStackTrace()
                                    }
                                    showSendPumpMessageMenu = false
                                },
                                leadingIcon = {}
                            )
                            DebugDivider()
                        }
                    }

                }
                DebugDivider()
            }

            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.debug_view_recv_mess_cache)) },
                    supportingContent = { Text(context.getString(R.string.debug_displays_recently_recv_pump_mess)) },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Build,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable {
                        showMessageCache = true
                    }
                )

                if (showMessageCache) {
                    Popup(
                        onDismissRequest = { showMessageCache = false }
                    ) {
                        LaunchedEffect(Unit) {
                            sendMessage(MessagePaths.TO_PUMP_DEBUG_MESSAGE_CACHE, "".toByteArray())
                        }

                        val debugMessageCache = ds.debugMessageCache.observeAsState()
                        DebugPopupSurface {
                            LazyColumn {
                                item {
                                    Spacer(Modifier.height(64.dp))
                                }
                                item {
                                    LazyRow(Modifier.fillMaxWidth()) {
                                        item {
                                            IconButton(
                                                onClick = {
                                                    showMessageCache = false
                                                }
                                            ) {
                                                Icon(Icons.Filled.Close, contentDescription = context.getString(R.string.debug_close))
                                            }
                                        }
                                        item {
                                            Spacer(Modifier.width(16.dp))
                                        }
                                        item {
                                            FilledTonalButton(
                                                onClick = {
                                                    debugMessageCache.value?.let {
                                                        shareTextContents(messageCacheToJson(it), context.getString(R.string.debug_ctrl_x2_json_debug_data),"text/json")
                                                    }
                                                }
                                            ) {
                                                Text(context.getString(R.string.debug_export))
                                            }
                                        }
                                    }
                                }
                                if (debugMessageCache.value?.isEmpty() == true) {
                                    item {
                                        Text(context.getString(R.string.debug_no_mess_entries_present_in_cache))
                                    }
                                }
                                debugMessageCache.value?.sortedBy {
                                    it.second.epochSecond * -1
                                }?.forEach { message ->
                                    item {
                                        ListItem(
                                            headlineContent = {
                                                // message name
                                                Text(shortPumpMessageTitle(message.first))
                                            },
                                            supportingContent = {
                                                // message detail
                                                Text(shortPumpMessageDetail(message.first))
                                            },
                                            overlineContent = {
                                                // time
                                                Text("${message.second} (${shortTimeAgo(message.second, prefix = stringResource(R.string.units_in), suffix = stringResource(R.string.units_ago), nowThresholdSeconds = 1, nowText = stringResource(R.string.units_now))})")
                                            },
                                            modifier = Modifier.clickable {
                                                shareTextContents(messagePairToJson(message), "ControlX2 ${shortPumpMessageTitle(message.first)} debug data","text/json")
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                DebugDivider()
            }

            // History Log Sync Progress Bar
            historyLogViewModel?.let {
                item {
                    HistoryLogSyncProgressBar(
                        historyLogViewModel = it,
                        replaceWithPaddingWhenComplete = true
                    )
                    DebugDivider()
                }
            }

            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.debug_get_history_logs)) },
                    supportingContent = { Text(context.getString(R.string.debug_fetches_history_logs_within_given_range)) },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Build,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable {
                        Timber.d("pressed Get History Logs")
                        coroutineScope.launch {
                            dataStore.historyLogStatus.value = null
                            sendPumpCommands(SendType.STANDARD, listOf(HistoryLogStatusRequest()))

                            for (i in 1..50) {
                                Timber.d("waiting for historyLogStatus: ${dataStore.historyLogStatus}")
                                if (dataStore.historyLogStatus.value != null) {
                                    break
                                }
                                delay(100)
                            }

                            triggerHistoryLogRequestDialog(
                                context,
                                sendPumpCommands,
                                dataStore.historyLogStatus.value
                            )
                        }
                    }
                )
                DebugDivider()
            }

            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.debug_view_history_log_mess)) },
                    supportingContent = { Text(context.getString(R.string.debug_displays_pump_history_log_mess)) },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Build,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable {
                        // 未拉取过历史日志时缓存为空，直接开弹窗会渲染出空白页面（点请求也无法立即得到数据），
                        // 因此先判断缓存是否有可显示的消息，无数据时只做轻量提示。
                        if (ds.historyLogCache.value?.isNotEmpty() != true) {
                            Toast.makeText(context, context.getString(R.string.debug_history_log_messages_not_loaded), Toast.LENGTH_SHORT).show()
                        } else {
                            sendMessage(MessagePaths.TO_PUMP_DEBUG_HISTORYLOG_CACHE, "".toByteArray())
                            Handler(Looper.getMainLooper()).postDelayed({
                                showHistoryLogs = true
                            }, 500)
                        }
                    }
                )

                if (showHistoryLogs) {
                    Popup(
                        onDismissRequest = { showHistoryLogs = false }
                    ) {
                        val historyLogCache = ds.historyLogCache.observeAsState()
                        var filterToType by remember { mutableStateOf<String?>(null) }
                        var filterFields by remember { mutableStateOf(historyLogCache.value?.entries?.map { shortHistoryLogPumpMessageTitle(it.value) }?.toSet()?.sorted()) }
                        LaunchedEffect (historyLogCache.value) {
                            filterFields = historyLogCache.value?.entries?.map { shortHistoryLogPumpMessageTitle(it.value) }?.toSet()?.sorted()
                        }
                        DebugPopupSurface {
                            LazyColumn {
                                item {
                                    Spacer(Modifier.height(64.dp))
                                }
                                item {
                                    LazyRow(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                                        item {
                                            IconButton(
                                                onClick = {
                                                    showHistoryLogs = false
                                                }
                                            ) {
                                                Icon(Icons.Filled.Close, contentDescription = context.getString(R.string.debug_close))
                                            }
                                        }
                                        item {
                                            Spacer(Modifier.width(16.dp))
                                        }
                                        item {
                                            FilledTonalButton(
                                                onClick = {
                                                    historyLogCache.value?.let {
                                                        shareTextContents(historyLogCacheToJson(it), context.getString(R.string.debug_ctrl_x2_history_log_json_data),"text/json")
                                                    }
                                                }
                                            ) {
                                                Text(context.getString(R.string.debug_export))
                                            }
                                        }
                                    }
                                }

                                item {
                                    var expanded by remember { mutableStateOf(false) }
                                    val icon = if (expanded)
                                        Icons.Filled.KeyboardArrowUp
                                    else
                                        Icons.Filled.KeyboardArrowDown

                                    var textFieldSize by remember { mutableStateOf(Size.Zero)}

                                    Box (Modifier
                                        .padding(start = 16.dp, end = 16.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = filterToType ?: context.getString(R.string.debug_all),
                                            onValueChange = { },
                                            readOnly = true,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .onGloballyPositioned { coordinates ->
                                                    // This value is used to assign to
                                                    // the DropDown the same width
                                                    textFieldSize = coordinates.size.toSize()
                                                },
                                            label = { Text(context.getString(R.string.debug_filter)) },
                                            trailingIcon = {
                                                Icon(
                                                    icon,
                                                    context.getString(R.string.debug_filter_dropdown_icon),
                                                    Modifier.clickable { expanded = !expanded }
                                                )
                                            },
                                            textStyle = TextStyle.Default.copy(fontSize = 16.sp),
                                        )
                                        // 只读展示：整行点击展开下拉，避免输入内容被当成过滤值
                                        Box(
                                            Modifier
                                                .matchParentSize()
                                                .clickable { expanded = !expanded }
                                        )

                                        DropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false },
                                            modifier = Modifier
                                                .width(with(LocalDensity.current) { textFieldSize.width.toDp() })
                                        ) {
                                            filterFields?.forEach { label ->
                                                DropdownMenuItem(onClick = {
                                                    filterToType = label
                                                    expanded = false
                                                }, text = {
                                                    Text(text = label)
                                                })
                                            }
                                        }
                                    }
                                }

                                if (historyLogCache.value?.entries?.isEmpty() == true) {
                                    item {
                                        Text(
                                            context.getString(R.string.debug_no_history_log_entries_present_in_cache),
                                            modifier = Modifier
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .fillMaxWidth()
                                                .padding(16.dp)
                                        )
                                    }
                                } else {
                                    item {
                                        Text(
                                            context.getString(
                                                R.string.debug_history_log_entries,
                                                (historyLogCache.value?.size ?: 0).toString()
                                            ),
                                            modifier = Modifier
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .fillMaxWidth()
                                                .padding(16.dp)
                                                .clickable {
                                                    sendMessage(MessagePaths.TO_PUMP_DEBUG_HISTORYLOG_CACHE, "".toByteArray())
                                                }
                                        )
                                    }
                                }
                                historyLogCache.value?.entries?.sortedBy {
                                    it.key * -1
                                }?.filter {
                                    if (filterToType == null) {
                                        true
                                    } else {
                                        shortHistoryLogPumpMessageTitle(it.value) == filterToType
                                    }
                                }?.forEach { log ->
                                    item {
                                        ListItem(
                                            headlineContent = {
//                                                // message name
                                                Text(shortHistoryLogPumpMessageTitle(log.value))
                                            },
                                            supportingContent = {
                                                // message detail
                                                Text("${log.value}")
                                            },
                                            overlineContent = {
                                                // time
                                                Text("#${log.key} ${log.value.pumpTimeSecInstant}")
                                            },
                                            modifier = Modifier.clickable {
                                                shareTextContents(historyLogToJson(log), context.getString(R.string.debug_ctrl_x2_history_log_event),"text/json")
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                DebugDivider()
            }

            // t:connect 共享连接特性已由上游关闭（FeatureFlags.ConnectionSharing=const false），此调试开关保留代码备查
//            if (FeatureFlags.ConnectionSharing && Prefs(context).connectionSharingEnabled()) {
//                item {
//                    if (Prefs(context).onlySnoopBluetoothEnabled()) {
//                        ListItem(
//                            headlineContent = { Text(context.getString(R.string.debug_disable_only_snoop_bt)) },
//                            supportingContent = { Text(context.getString(R.string.debug_re_enables_app_functionality)) },
//                            leadingContent = {
//                                Icon(
//                                    Icons.Filled.Close,
//                                    contentDescription = context.getString(R.string.debug_stop_icon),
//                                )
//                            },
//                            modifier = Modifier.clickable {
//                                Prefs(context).setOnlySnoopBluetoothEnabled(false)
//                                coroutineScope.launch {
//                                    delay(250)
//                                    sendMessage(MessagePaths.TO_SERVER_FORCE_RELOAD, "".toByteArray())
//                                }
//                            }
//                        )
//                    } else {
//                        ListItem(
//                            headlineContent = { Text(context.getString(R.string.debug_enable_only_snoop_bt)) },
//                            supportingContent = { Text(context.getString(R.string.debug_all_app_functionality_disabled)) },
//                            leadingContent = {
//                                Icon(
//                                    Icons.Filled.Check,
//                                    contentDescription = context.getString(R.string.debug_start_icon),
//                                )
//                            },
//                            modifier = Modifier.clickable {
//                                Prefs(context).setOnlySnoopBluetoothEnabled(true)
//                                coroutineScope.launch {
//                                    delay(250)
//                                    sendMessage(MessagePaths.TO_SERVER_FORCE_RELOAD, "".toByteArray())
//                                }
//                            }
//                        )
//                    }
//                    DebugDivider()
//                }
//            }

            // G3
            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.debug_clear_debug_logs)) },
                    supportingContent = { Text(context.getString(R.string.debug_clears_saved_debug_logs)) },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable {
                        clearDebugLog(context)
                    }
                )
                DebugDivider()
            }

            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.debug_empty_database)) },
                    supportingContent = { Text(context.getString(R.string.debug_remove_all_saved_history_logs_sqlite)) },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable {
                        emptyDatabase(context)
                    }
                )
                DebugDivider()
            }

            // G4
            item {
                var qualifyingEventToastsEnabled by remember { mutableStateOf(Prefs(context).qualifyingEventToastsEnabled()) }
                ListItem(
                    headlineContent = { Text(if (qualifyingEventToastsEnabled) context.getString(R.string.debug_disable_qualifying_event_toasts) else context.getString(R.string.debug_enable_qualifying_event_toasts)) },
                    supportingContent = { Text(context.getString(R.string.debug_toast_on_qualifying_events)) },
                    leadingContent = {
                        Icon(
                            if (qualifyingEventToastsEnabled) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier
                        .clickable {
                            qualifyingEventToastsEnabled = !qualifyingEventToastsEnabled
                            Prefs(context).setQualifyingEventToastsEnabled(qualifyingEventToastsEnabled)
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (qualifyingEventToastsEnabled) {
                                        R.string.debug_qualifying_event_toasts_enabled
                                    } else {
                                        R.string.debug_qualifying_event_toasts_disabled
                                    }
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .semantics {
                            stateDescription = context.getString(
                                if (qualifyingEventToastsEnabled) R.string.pumppan_toggle_on else R.string.pumppan_toggle_off
                            )
                        }
                )
                DebugDivider()
            }

            item {
                var httpDebugApiEnabled by remember { mutableStateOf(Prefs(context).httpDebugApiEnabled()) }
                ListItem(
                    headlineContent = { Text(if (httpDebugApiEnabled) context.getString(R.string.debug_disable_http_debug_api) else context.getString(R.string.debug_enable_http_debug_api)) },
                    supportingContent = { Text(context.getString(R.string.debug_toggle_http_debug_api_on_port)) },
                    leadingContent = {
                        Icon(
                            if (httpDebugApiEnabled) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier
                        .semantics {
                            stateDescription = context.getString(
                                if (httpDebugApiEnabled) R.string.pumppan_toggle_on else R.string.pumppan_toggle_off
                            )
                        }
                        .clickable {
                            httpDebugApiEnabled = !httpDebugApiEnabled
                        Prefs(context).setHttpDebugApiEnabled(httpDebugApiEnabled)
                        Toast.makeText(
                            context,
                            context.getString(
                                if (httpDebugApiEnabled) {
                                    R.string.debug_http_debug_api_enabled_restart
                                } else {
                                    R.string.debug_http_debug_api_disabled_restart
                                }
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )

                if (httpDebugApiEnabled) {
                    DebugDivider()
                    var username by remember { mutableStateOf(Prefs(context).httpDebugApiUsername()) }
                    ListItem(
                        headlineContent = { Text(context.getString(R.string.debug_api_username)) },
                        supportingContent = {
                            OutlinedTextField(
                                value = username,
                                onValueChange = {
                                    username = it
                                    Prefs(context).setHttpDebugApiUsername(it)
                                },
                                label = { Text(context.getString(R.string.debug_username)) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                            )
                        }
                    )
                    DebugDivider()
                    var password by remember { mutableStateOf(Prefs(context).httpDebugApiPassword()) }
                    var passwordVisible by remember { mutableStateOf(false) }

                    ListItem(
                        headlineContent = { Text(context.getString(R.string.debug_api_password)) },
                        supportingContent = {
                            OutlinedTextField(
                                value = password,
                                onValueChange = {
                                    password = it
                                    Prefs(context).setHttpDebugApiPassword(it)
                                },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                label = { Text(context.getString(R.string.debug_password)) },
                                trailingIcon = {
                                    val image = if (passwordVisible)
                                        Icons.Filled.Visibility
                                    else Icons.Filled.VisibilityOff

                                    // Please provide localized description for accessibility services
                                    val description = if (passwordVisible) context.getString(R.string.debug_hide_password) else context.getString(R.string.debug_show_password)

                                    IconButton(onClick = {passwordVisible = !passwordVisible}){
                                        Icon(imageVector  = image, description)
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                            )
                        }
                    )
                }
                DebugDivider()
            }

            // G5
            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.debug_view_pump_state)) },
                    supportingContent = { Text(context.getString(R.string.debug_displays_pump_mac_pairing_key_and)) },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable {
                        showPumpState = true
                    }
                )

                if (showPumpState) {
                    val exportedPumpState = PumpState.exportState(context)
                    Popup(
                        onDismissRequest = { showPumpState = false }
                    ) {

                        DebugPopupSurface {
                            LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                                item {
                                    Spacer(Modifier.height(64.dp))
                                }
                                item {
                                    TextField(
                                        value = exportedPumpState,
                                        label = { Text(context.getString(R.string.debug_pump_state)) },
                                        onValueChange = {v -> },
                                        modifier = Modifier.fillMaxWidth().height(200.dp).padding(10.dp)
                                    )
                                }

                                item {
                                    LazyRow {
                                        item {
                                            Button(onClick = {
                                                setClipboard(exportedPumpState)
                                            }) {
                                                Text(context.getString(R.string.debug_save_to_clipboard))
                                            }
                                        }
                                        item {
                                            Spacer(Modifier.width(16.dp))
                                        }
                                        item {
                                            Button(onClick = {
                                                showPumpState = false
                                            }) {
                                                Text(context.getString(R.string.debug_close))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                DebugDivider()
            }

            item {
                com.pumppan.controlx2.PumppanPumpModelRow()
                DebugDivider()
            }

            // G6
            item {
                ListItem(
                    headlineContent = { Text(context.getString(R.string.debug_feature_flags)) },
                    supportingContent = { Text(context.getString(R.string.debug_toggle_experimental_features)) },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable { navigateToFeatureFlags() },
                )
                DebugDivider()
            }

            // 页尾：返回（不属于任何分组）
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.app_back)) },
                    leadingContent = { Icon(Icons.Filled.ArrowBack, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = navigateBack),
                )
            }
        }
    )

    if (showSupportBundleDialog && supportBundleSummary != null) {
        val summary = supportBundleSummary!!
        AlertDialog(
            onDismissRequest = {
                showSupportBundleDialog = false
                supportBundleSummary = null
            },
            title = { Text(context.getString(R.string.settings_send_pump_x2_support_bundle)) },
            text = {
                Text(context.getString(R.string.settings_send_debug_files_with_logs_query, summary.debugFileCount, formatLogLineCount(summary.totalLogLines), summary.rangeStart, summary.rangeEnd))
            },
            confirmButton = {
                TextButton(onClick = {
                    sendSupportBundleEmail(context)
                    showSupportBundleDialog = false
                    supportBundleSummary = null
                }) {
                    Text(context.getString(R.string.settings_send_email))
                }

                TextButton(onClick = {
                    shareSupportBundle(context)
                    showSupportBundleDialog = false
                    supportBundleSummary = null
                }) {
                    Text(context.getString(R.string.settings_share))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSupportBundleDialog = false
                    supportBundleSummary = null
                }) {
                    Text(context.getString(R.string.debug_cancel))
                }
            }
        )
    }
}


fun triggerIDPSegmentDialog(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle(context.getString(R.string.debug_enter_idp_id))
    builder.setMessage(context.getString(R.string.debug_enter_id_for_insulin_delivery_profile))
    val input1 = EditText(context)
    input1.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
    builder.setView(input1)
    builder.setPositiveButton(context.getString(R.string.debug_ok)) { dialog, which ->
        val idpId = input1.text.toString()
        Timber.i("idp id: %s", idpId)
        val builder2 = AlertDialog.Builder(context)
        builder2.setTitle(context.getString(R.string.debug_enter_segment_index))
        builder2.setMessage(context.getString(R.string.debug_enter_index_for_insulin_delivery))
        val input2 = EditText(context)
        input2.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        builder2.setView(input2)
        builder2.setPositiveButton(
            context.getString(R.string.debug_ok)
        ) { dialog, which ->
            val idpSegment = input2.text.toString()
            Timber.i("idp segment: %s", idpSegment)
            sendPumpCommands(
                SendType.DEBUG_PROMPT,
                listOf(
                    IDPSegmentRequest(idpId.toInt(), idpSegment.toInt())
                )
            )
        }
        builder2.setNegativeButton(
            context.getString(R.string.debug_cancel)
        ) { dialog, which -> dialog.cancel() }
        builder2.show()
    }
    builder.setNegativeButton(
        context.getString(R.string.debug_cancel)
    ) { dialog, which -> dialog.cancel() }
    builder.show()
}

fun triggerIDPSettingsDialog(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle(context.getString(R.string.debug_enter_idp_id))
    builder.setMessage(context.getString(R.string.debug_enter_id_for_insulin_delivery_profile))
    val input1 = EditText(context)
    input1.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
    builder.setView(input1)
    builder.setPositiveButton(context.getString(R.string.debug_ok)) { dialog, which ->
        val idpId = input1.text.toString()
        Timber.i("idp id: %s", idpId)
        sendPumpCommands(
            SendType.DEBUG_PROMPT,
            listOf(
                IDPSettingsRequest(idpId.toInt())
            )
        )
    }
    builder.setNegativeButton(
        context.getString(R.string.debug_cancel)
    ) { dialog, which -> dialog.cancel() }
    builder.show()
}

fun triggerHistoryLogRequestDialog(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    historyLogStatus: HistoryLogStatusResponse? = null,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle(context.getString(R.string.debug_enter_start_log_id))
    builder.setMessage(
        if (historyLogStatus == null) {
            context.getString(R.string.debug_history_log_range_unavailable)
        } else {
            context.getString(R.string.debug_enter_first_log_id_with_range, historyLogStatus.toString())
        }
    )
    val input1 = EditText(context)
    input1.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL
    input1.width = 200
    if (historyLogStatus != null) {
        input1.setText("${max(0, historyLogStatus.lastSequenceNum-255)}")
    }
    val layout = LinearLayout(context)
    layout.addView(input1)

    if (historyLogStatus != null) {
        val slider = Slider(context)
        slider.valueFrom = historyLogStatus.firstSequenceNum.toFloat()
        slider.valueTo = historyLogStatus.lastSequenceNum.toFloat()
        slider.value = max(0, historyLogStatus.lastSequenceNum-255).toFloat()
        slider.stepSize = 1.0f
        slider.addOnChangeListener { slider, value, fromUser -> input1.setText("${value.toLong()}") }
        layout.addView(slider)
    }

    builder.setView(layout)
    builder.setPositiveButton(context.getString(R.string.debug_ok)) { dialog, which ->
        val startLog = input1.text.toString()
        Timber.i("startLog id: %s", startLog)
        val builder2 = AlertDialog.Builder(context)
        builder2.setTitle(context.getString(R.string.debug_enter_number_of_logs))
        builder2.setMessage(context.getString(R.string.debug_enter_max_number_of_logs_to_return))
        val input2 = EditText(context)
        input2.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL
        if (historyLogStatus != null) {
            input2.setText("${min(startLog.toLong()+255, historyLogStatus.lastSequenceNum)-startLog.toLong()}")
        }
        builder2.setView(input2)

        if (historyLogStatus != null) {
            builder2.setNeutralButton(context.getString(R.string.debug_get_all)) { dialog, which ->
                triggerHistoryLogRangePrompt(context, sendPumpCommands, startLog.toLong(), historyLogStatus.lastSequenceNum)
            }
        }
        builder2.setPositiveButton(
            context.getString(R.string.debug_ok)
        ) { dialog, which ->
            val maxLogs = input2.text.toString()
            Timber.i("max logs: %s", maxLogs)
            triggerHistoryLogRangePrompt(context, sendPumpCommands, startLog.toLong(), startLog.toLong()+maxLogs.toLong())

        }
        builder2.setNegativeButton(
            context.getString(R.string.debug_cancel)
        ) { dialog, which -> dialog.cancel() }
        builder2.show()
    }
    builder.setNegativeButton(
        context.getString(R.string.debug_cancel)
    ) { dialog, which -> dialog.cancel() }
    builder.show()
}

fun triggerHistoryLogRangePrompt(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    startLog: Long,
    endLog: Long
) {
    if (endLog - startLog in 0..255) {
        // HistoryLogRequest returns items backward from the start ID,
        // so we request from the END of the range to get all items in [startLog, endLog]
        sendPumpCommands(
            SendType.STANDARD,
            listOf(
                HistoryLogRequest(endLog, (endLog - startLog).toInt())
            )
        )
        return
    }

    val chunkCount = ceil(((endLog - startLog) / 255).toDouble())
    AlertDialog.Builder(context)
        .setTitle(context.getString(R.string.debug_history_log_request))
        .setMessage(context.getString(R.string.debug_seq_range_requires_chunks_query, startLog, endLog, chunkCount))
        .setPositiveButton(context.getString(R.string.debug_ok)) { dialog, which ->
            Handler(Looper.getMainLooper()).post {
                triggerHistoryLogRange(context, sendPumpCommands, startLog, endLog)
            }
        }
        .setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, which -> dialog.cancel() }
        .show()
}

fun triggerHistoryLogRange(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    startLog: Long,
    endLog: Long
)  {
    var num = 0
    var totalNums = 1
    for (i in startLog..endLog step 256) {
        val localNum = num
        val count = if (i+255 > endLog) (endLog - i).toInt() else 255
        val endI = i + count - 1
        Handler(Looper.getMainLooper()).postDelayed({
            Timber.i("HistoryLogRequest ${localNum+1} from $i to $endI count=$count")
            // HistoryLogRequest returns items backward from the start ID,
            // so we request from the END of the range to get all items in [i, endI]
            sendPumpCommands(
                SendType.STANDARD,
                listOf(
                    HistoryLogRequest(endI, count)
                )
            )
            Toast.makeText(context, context.getString(R.string.debug_requesting_i_to_end, localNum+1, totalNums, i, endI), Toast.LENGTH_SHORT).show()
        }, localNum.toLong() * 7000)
        num++
        totalNums++
    }
}

fun triggerInitiateBolusRequestDialog(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle(context.getString(R.string.debug_enter_units_to_deliver_bolus))
    builder.setMessage(context.getString(R.string.debug_enter_number_of_units_in_integer))
    val input1 = EditText(context)
    input1.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
    builder.setView(input1)
    builder.setPositiveButton(context.getString(R.string.debug_ok), DialogInterface.OnClickListener { dialog, which ->
        val numUnitsStr = input1.text.toString()
        Timber.i("numUnits: %s", numUnitsStr)
        if ("" == numUnitsStr) {
            Timber.e("Not delivering bolus because no units entered.")
            return@OnClickListener
        }
        val builder2 = AlertDialog.Builder(context)
        builder2.setTitle(context.getString(R.string.debug_confirm_bolus))
        builder2.setMessage(context.getString(R.string.debug_enter_bolus_id_from_bolus_permission))
        val input2 = EditText(context)
        input2.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        builder2.setView(input2)
        builder2.setPositiveButton(context.getString(R.string.debug_ok),
            DialogInterface.OnClickListener { dialog, which ->
                val bolusIdStr = input2.text.toString()
                Timber.i("currentIob: %s", bolusIdStr)
                if ("" == bolusIdStr) {
                    Timber.e("Not delivering bolus because no bolus ID entered.")
                    return@OnClickListener
                }
                val numUnits = numUnitsStr.toInt()
                val bolusId = bolusIdStr.toInt()
                // tandemEventCallback.lastBolusId = bolusId
                // InitiateBolusRequest(long totalVolume, int bolusTypeBitmask, long foodVolume, long correctionVolume, int bolusCarbs, int bolusBG, long bolusIOB)
                sendPumpCommands(
                    SendType.DEBUG_PROMPT,
                    listOf(
                        InitiateBolusRequest(
                            numUnits.toLong(),
                            bolusId,
                            BolusDeliveryHistoryLog.BolusType.toBitmask(BolusDeliveryHistoryLog.BolusType.FOOD2),
                            0L,
                            0L,
                            0,
                            0,
                            0
                        )
                    )
                )
            })
        builder2.setNegativeButton(
            context.getString(R.string.debug_cancel)
        ) { dialog, which -> dialog.cancel() }
        builder2.show()
    })
    builder.setNegativeButton(
        context.getString(R.string.debug_cancel)
    ) { dialog, which -> dialog.cancel() }
    builder.show()
}

fun triggerCancelBolusRequestDialog(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("CancelBolusRequest")
    builder.setMessage(context.getString(R.string.debug_enter_bolus_id_this_can_be))
    val input1 = EditText(context)
    input1.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
//    if (tandemEventCallback.lastBolusId > 0) {
//        input1.setText(java.lang.String.valueOf(tandemEventCallback.lastBolusId))
//    }
    builder.setView(input1)
    builder.setPositiveButton(context.getString(R.string.debug_ok), DialogInterface.OnClickListener { dialog, which ->
        val bolusIdStr = input1.text.toString()
        Timber.i("bolusId: %s", bolusIdStr)
        if ("" == bolusIdStr) {
            Timber.e("Not cancelling bolus because no units entered.")
            return@OnClickListener
        }
        val bolusId = bolusIdStr.toInt()
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(CancelBolusRequest(bolusId)))
    })
    builder.setNegativeButton(
        context.getString(R.string.debug_cancel)
    ) { dialog, which -> dialog.cancel() }
    builder.show()
}

fun triggerMessageWithBolusIdParameter(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    messageClass: Class<out Message>
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle(messageClass.simpleName)
    builder.setMessage(context.getString(R.string.debug_enter_bolus_id_can_be_recv_from_in_prog))
    val input1 = EditText(context)
    input1.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
//    if (tandemEventCallback.lastBolusId > 0) {
//        input1.setText(java.lang.String.valueOf(tandemEventCallback.lastBolusId))
//    }
    builder.setView(input1)
    builder.setPositiveButton(context.getString(R.string.debug_ok), DialogInterface.OnClickListener { dialog, which ->
        val bolusIdStr = input1.text.toString()
        Timber.i("bolusId: %s", bolusIdStr)
        if ("" == bolusIdStr) {
            Timber.e("Not sending message because no bolus ID entered.")
            return@OnClickListener
        }
        val bolusId = bolusIdStr.toLong()
        val constructor = messageClass.constructors
            .filterIsInstance<Constructor<out Message>>()
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && it.parameterCount == 1 }
            .firstOrNull { ctor ->
                val type = ctor.parameterTypes[0]
                type == Int::class.javaPrimitiveType || type == Int::class.java ||
                    type == Long::class.javaPrimitiveType || type == Long::class.java
            }
        if (constructor == null) {
            Timber.e("No constructor with numeric bolus ID parameter found for %s", messageClass.name)
            Toast.makeText(context, "No bolus ID constructor available for ${messageClass.simpleName}", Toast.LENGTH_SHORT).show()
            return@OnClickListener
        }
        try {
            val paramType = constructor.parameterTypes[0]
            val message = if (paramType == Int::class.javaPrimitiveType || paramType == Int::class.java) {
                constructor.newInstance(bolusId.toInt())
            } else {
                constructor.newInstance(bolusId)
            }
            sendPumpCommands(SendType.DEBUG_PROMPT, listOf(message))
        } catch (e: IllegalAccessException) {
            Timber.e(e)
        } catch (e: InstantiationException) {
            Timber.e(e)
        } catch (e: InvocationTargetException) {
            Timber.e(e)
        }
    })
    builder.setNegativeButton(
        context.getString(R.string.debug_cancel)
    ) { dialog, which -> dialog.cancel() }
    builder.show()
}

fun triggerGenericPumpMessageDialog(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    messageClass: Class<out Message>,
) {
    val constructors = messageClass.constructors
        .filterIsInstance<Constructor<out Message>>()
        .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }

    val noArgConstructor = constructors.firstOrNull { it.parameterCount == 0 }
    val argumentConstructors = constructors
        .filter { it.parameterCount > 0 }
        .filter { ctor -> ctor.parameterTypes.none { it == ByteArray::class.java } }
        .filter { ctor -> ctor.parameterTypes.all { isSupportedDebugConstructorType(it) } }
        .sortedBy { it.parameterCount }

    if (argumentConstructors.isEmpty()) {
        if (noArgConstructor == null) {
            Toast.makeText(context, context.getString(R.string.debug_no_usable_constructor_found_for, messageClass.simpleName), Toast.LENGTH_SHORT).show()
            return
        }
        sendConstructedPumpMessage(context, sendPumpCommands, noArgConstructor, emptyList())
        return
    }

    val options = mutableListOf<String>()
    val optionConstructors = mutableListOf<Constructor<out Message>?>()
    if (noArgConstructor != null) {
        options.add(context.getString(R.string.debug_no_arguments))
        optionConstructors.add(noArgConstructor)
    }
    argumentConstructors.forEach {
        options.add(formatDebugConstructorSignature(messageClass, it))
        optionConstructors.add(it)
    }

    if (optionConstructors.size == 1) {
        val onlyConstructor = optionConstructors.first()
        if (onlyConstructor == null || onlyConstructor.parameterCount == 0) {
            sendConstructedPumpMessage(context, sendPumpCommands, noArgConstructor, emptyList())
        } else {
            triggerConstructorArgumentDialog(context, sendPumpCommands, messageClass, onlyConstructor)
        }
        return
    }

    AlertDialog.Builder(context)
        .setTitle(context.getString(R.string.debug_constructor, messageClass.simpleName))
        .setItems(options.toTypedArray()) { dialog, which ->
            val selected = optionConstructors[which]
            if (selected == null || selected.parameterCount == 0) {
                sendConstructedPumpMessage(context, sendPumpCommands, noArgConstructor, emptyList())
            } else {
                triggerConstructorArgumentDialog(context, sendPumpCommands, messageClass, selected)
            }
        }
        .setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
        .show()
}

fun triggerConstructorArgumentDialog(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    messageClass: Class<out Message>,
    constructor: Constructor<out Message>,
) {
    val layout = LinearLayout(context)
    layout.orientation = LinearLayout.VERTICAL
    val inputs = mutableListOf<EditText>()
    constructor.parameterTypes.forEachIndexed { index, paramType ->
        val input = EditText(context)
        input.hint = "arg${index + 1}: ${debugTypeHint(context, paramType)}"
        input.inputType = debugInputType(paramType)
        layout.addView(input)
        inputs.add(input)
    }

    AlertDialog.Builder(context)
        .setTitle(formatDebugConstructorSignature(messageClass, constructor))
        .setView(layout)
        .setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
            val args = mutableListOf<Any>()
            for ((index, paramType) in constructor.parameterTypes.withIndex()) {
                val raw = inputs[index].text.toString().trim()
                if (raw.isBlank()) {
                    Toast.makeText(context, context.getString(R.string.debug_argument_is_required, index + 1), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                try {
                    args.add(parseDebugConstructorArg(context, raw, paramType))
                } catch (e: Exception) {
                    Timber.e(e)
                    Toast.makeText(
                        context,
                        "Invalid value for argument ${index + 1} (${debugTypeHint(context, paramType)})",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
            }
            sendConstructedPumpMessage(context, sendPumpCommands, constructor, args)
            dialog.dismiss()
        }
        .setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
        .show()
}

fun sendConstructedPumpMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
    constructor: Constructor<out Message>?,
    args: List<Any>,
) {
    if (constructor == null) {
        Toast.makeText(context, context.getString(R.string.debug_no_constructor_available), Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val message = constructor.newInstance(*args.toTypedArray())
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(message))
    } catch (e: IllegalAccessException) {
        Timber.e(e)
        Toast.makeText(context, "Unable to access constructor", Toast.LENGTH_SHORT).show()
    } catch (e: InstantiationException) {
        Timber.e(e)
        Toast.makeText(context, "Unable to create message", Toast.LENGTH_SHORT).show()
    } catch (e: InvocationTargetException) {
        Timber.e(e)
        Toast.makeText(context, "Message constructor failed", Toast.LENGTH_SHORT).show()
    }
}

fun formatDebugConstructorSignature(
    messageClass: Class<out Message>,
    constructor: Constructor<out Message>,
): String {
    return "${messageClass.simpleName}(" + constructor.parameterTypes.joinToString(", ") { it.simpleName } + ")"
}

fun isSupportedDebugConstructorType(type: Class<*>): Boolean {
    if (type == ByteArray::class.java) {
        return false
    }
    if (type.isEnum) {
        return true
    }
    return type == String::class.java ||
        type == Instant::class.java ||
        type == Int::class.javaPrimitiveType || type == Int::class.java ||
        type == Long::class.javaPrimitiveType || type == Long::class.java ||
        type == Boolean::class.javaPrimitiveType || type == Boolean::class.java ||
        type == Float::class.javaPrimitiveType || type == Float::class.java ||
        type == Double::class.javaPrimitiveType || type == Double::class.java ||
        type == Short::class.javaPrimitiveType || type == Short::class.java ||
        type == Byte::class.javaPrimitiveType || type == Byte::class.java
}

fun debugTypeHint(context: Context, type: Class<*>): String {
    if (type == Instant::class.java) {
        return context.getString(R.string.debug_instant_epoch_seconds_or_iso_8601)
    }
    if (type.isEnum) {
        val values = type.enumConstants
            ?.map { (it as Enum<*>).name }
            ?.joinToString("|")
            ?: ""
        return "${type.simpleName} [$values]"
    }
    return type.simpleName
}

fun debugInputType(type: Class<*>): Int {
    return when {
        type == Int::class.javaPrimitiveType || type == Int::class.java ||
            type == Long::class.javaPrimitiveType || type == Long::class.java ||
            type == Short::class.javaPrimitiveType || type == Short::class.java ||
            type == Byte::class.javaPrimitiveType || type == Byte::class.java ->
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED

        type == Float::class.javaPrimitiveType || type == Float::class.java ||
            type == Double::class.javaPrimitiveType || type == Double::class.java ->
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED

        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
    }
}

fun parseDebugConstructorArg(context: Context, raw: String, type: Class<*>): Any {
    return when {
        type == String::class.java -> raw
        type == Int::class.javaPrimitiveType || type == Int::class.java -> raw.toInt()
        type == Long::class.javaPrimitiveType || type == Long::class.java -> raw.toLong()
        type == Boolean::class.javaPrimitiveType || type == Boolean::class.java -> parseBoolInput(raw)
        type == Float::class.javaPrimitiveType || type == Float::class.java -> raw.toFloat()
        type == Double::class.javaPrimitiveType || type == Double::class.java -> raw.toDouble()
        type == Short::class.javaPrimitiveType || type == Short::class.java -> raw.toShort()
        type == Byte::class.javaPrimitiveType || type == Byte::class.java -> raw.toByte()
        type == Instant::class.java -> if (raw.matches(Regex("^-?\\d+$"))) Instant.ofEpochSecond(raw.toLong()) else Instant.parse(raw)
        type.isEnum -> {
            val enumValue = type.enumConstants?.firstOrNull {
                (it as Enum<*>).name.equals(raw, ignoreCase = true)
            }
            if (enumValue != null) {
                enumValue
            } else {
                throw IllegalArgumentException(context.getString(R.string.debug_invalid_enum_value_raw_for, raw, type.name))
            }
        }
        else -> throw IllegalArgumentException(context.getString(R.string.debug_unsupported_parameter_type, type.name))
    }
}


fun parseBoolInput(text: String): Boolean = text == "1" || text.equals("true", ignoreCase = true)

fun triggerRemoteBgEntryRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("RemoteBgEntryRequest")
    val layout = LinearLayout(context)
    layout.orientation = LinearLayout.VERTICAL
    val bgInput = EditText(context)
    val glucoseUnit = dataStore.glucoseUnitPreference.value ?: GlucoseUnit.MGDL
    bgInput.hint = context.getString(R.string.debug_bg, glucoseUnit.abbreviation)
    bgInput.inputType = InputType.TYPE_CLASS_NUMBER
    val calibrationInput = EditText(context)
    calibrationInput.hint = context.getString(R.string.debug_use_for_cgm_calibration_true_false_1_0)
    val autopopInput = EditText(context)
    autopopInput.hint = context.getString(R.string.debug_is_autopop_bg_true_false_1_0)
    val pumpTimeInput = EditText(context)
    pumpTimeInput.hint = context.getString(R.string.debug_pump_time_seconds_since_boot)
    pumpTimeInput.inputType = InputType.TYPE_CLASS_NUMBER
    val bolusIdInput = EditText(context)
    bolusIdInput.hint = context.getString(R.string.debug_bolus_id)
    bolusIdInput.inputType = InputType.TYPE_CLASS_NUMBER
    listOf(bgInput, calibrationInput, autopopInput, pumpTimeInput, bolusIdInput).forEach {
        layout.addView(it)
    }
    builder.setView(layout)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        val message = RemoteBgEntryRequest(
            bgInput.text.toString().toInt(),
            parseBoolInput(calibrationInput.text.toString()),
            parseBoolInput(autopopInput.text.toString()),
            pumpTimeInput.text.toString().toLong(),
            bolusIdInput.text.toString().toInt(),
        )
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(message))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerRemoteCarbEntryRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("RemoteCarbEntryRequest")
    val layout = LinearLayout(context)
    layout.orientation = LinearLayout.VERTICAL
    val carbsInput = EditText(context)
    carbsInput.hint = context.getString(R.string.debug_carbs_g)
    carbsInput.inputType = InputType.TYPE_CLASS_NUMBER
    val pumpTimeInput = EditText(context)
    pumpTimeInput.hint = context.getString(R.string.debug_pump_time_seconds_since_boot)
    pumpTimeInput.inputType = InputType.TYPE_CLASS_NUMBER
    val bolusIdInput = EditText(context)
    bolusIdInput.hint = context.getString(R.string.debug_bolus_id)
    bolusIdInput.inputType = InputType.TYPE_CLASS_NUMBER
    listOf(carbsInput, pumpTimeInput, bolusIdInput).forEach { layout.addView(it) }
    builder.setView(layout)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        val message = RemoteCarbEntryRequest(
            carbsInput.text.toString().toInt(),
            pumpTimeInput.text.toString().toLong(),
            bolusIdInput.text.toString().toInt(),
        )
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(message))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerSetModesRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("SetModesRequest")
    val input = EditText(context)
    input.hint = context.getString(R.string.debug_mode_bitmap_1_4)
    input.inputType = InputType.TYPE_CLASS_NUMBER
    builder.setView(input)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        val bitmap = input.text.toString().toInt()
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(SetModesRequest(bitmap)))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerSetSleepScheduleRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("SetSleepScheduleRequest")
    val layout = LinearLayout(context)
    layout.orientation = LinearLayout.VERTICAL
    val slotInput = EditText(context)
    slotInput.hint = context.getString(R.string.debug_slot)
    slotInput.inputType = InputType.TYPE_CLASS_NUMBER
    val scheduleInput = EditText(context)
    scheduleInput.hint = context.getString(R.string.debug_sched_bytes_comma_separated)
    val flagInput = EditText(context)
    flagInput.hint = context.getString(R.string.debug_flag)
    flagInput.inputType = InputType.TYPE_CLASS_NUMBER
    listOf(slotInput, scheduleInput, flagInput).forEach { layout.addView(it) }
    builder.setView(layout)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        val scheduleBytes = scheduleInput.text.toString()
            .split(',')
            .filter { it.isNotBlank() }
            .map { it.trim().toInt().toByte() }
            .toByteArray()
        val message = SetSleepScheduleRequest(
            slotInput.text.toString().toInt(),
            scheduleBytes,
            flagInput.text.toString().toInt(),
        )
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(message))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerSetTempRateRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("SetTempRateRequest")
    val layout = LinearLayout(context)
    layout.orientation = LinearLayout.VERTICAL
    val minutesInput = EditText(context)
    minutesInput.hint = context.getString(R.string.debug_minutes_15)
    minutesInput.inputType = InputType.TYPE_CLASS_NUMBER
    val percentInput = EditText(context)
    percentInput.hint = context.getString(R.string.debug_percent_0_250)
    percentInput.inputType = InputType.TYPE_CLASS_NUMBER
    listOf(minutesInput, percentInput).forEach { layout.addView(it) }
    builder.setView(layout)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        val message = SetTempRateRequest(
            minutesInput.text.toString().toInt(),
            percentInput.text.toString().toInt(),
        )
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(message))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerChangeControlIQSettingsRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("ChangeControlIQSettingsRequest")
    val layout = LinearLayout(context)
    layout.orientation = LinearLayout.VERTICAL
    val enabledInput = EditText(context)
    enabledInput.hint = context.getString(R.string.debug_enabled_true_false_1_0)
    val weightInput = EditText(context)
    weightInput.hint = context.getString(R.string.debug_weight_lbs)
    weightInput.inputType = InputType.TYPE_CLASS_NUMBER
    val tdiInput = EditText(context)
    tdiInput.hint = context.getString(R.string.debug_total_daily_insulin_units)
    tdiInput.inputType = InputType.TYPE_CLASS_NUMBER
    listOf(enabledInput, weightInput, tdiInput).forEach { layout.addView(it) }
    builder.setView(layout)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        val message = ChangeControlIQSettingsRequest(
            parseBoolInput(enabledInput.text.toString()),
            weightInput.text.toString().toInt(),
            tdiInput.text.toString().toInt(),
        )
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(message))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerSetQuickBolusSettingsRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("SetQuickBolusSettingsRequest")
    val layout = LinearLayout(context)
    layout.orientation = LinearLayout.VERTICAL
    val enabledInput = EditText(context)
    enabledInput.hint = context.getString(R.string.debug_enabled_true_false_1_0)
    val modeInput = EditText(context)
    modeInput.hint = context.getString(R.string.debug_mode_units_or_carbs)
    val incrementInput = EditText(context)
    incrementInput.hint = context.getString(R.string.debug_increment_enum_e_g_units_0_5)
    listOf(enabledInput, modeInput, incrementInput).forEach { layout.addView(it) }
    builder.setView(layout)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        val enabled = parseBoolInput(enabledInput.text.toString())
        val mode = SetQuickBolusSettingsRequest.QuickBolusMode.valueOf(modeInput.text.toString().uppercase())
        val increment = SetQuickBolusSettingsRequest.QuickBolusIncrement.valueOf(
            incrementInput.text.toString().uppercase()
        )
        val message = SetQuickBolusSettingsRequest(enabled, mode, increment)
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(message))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerDismissNotificationRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("DismissNotificationRequest")
    val layout = LinearLayout(context)
    layout.orientation = LinearLayout.VERTICAL
    val notificationIdInput = EditText(context)
    notificationIdInput.hint = context.getString(R.string.debug_notification_id)
    notificationIdInput.inputType = InputType.TYPE_CLASS_NUMBER
    val notificationTypeInput = EditText(context)
    notificationTypeInput.hint = context.getString(R.string.debug_notice_type_0_3)
    notificationTypeInput.inputType = InputType.TYPE_CLASS_NUMBER
    listOf(notificationIdInput, notificationTypeInput).forEach { layout.addView(it) }
    builder.setView(layout)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        val message = DismissNotificationRequest(
            DismissNotificationRequest.NotificationType.fromId(
                notificationTypeInput.text.toString().toInt()
            ) ?: DismissNotificationRequest.NotificationType.REMINDER,
            notificationIdInput.text.toString().toLong(),
        )
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(message))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerSetG6TransmitterIdRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("SetG6TransmitterIdRequest")
    val input = EditText(context)
    input.hint = context.getString(R.string.debug_transmitter_id)
    builder.setView(input)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(SetG6TransmitterIdRequest(input.text.toString())))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerStartDexcomG6SensorSessionRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("StartDexcomG6SensorSessionRequest")
    val input = EditText(context)
    input.hint = context.getString(R.string.debug_sensor_code_0_for_no)
    input.inputType = InputType.TYPE_CLASS_NUMBER
    builder.setView(input)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        val code = if (input.text.isNullOrBlank()) 0 else input.text.toString().toInt()
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(StartDexcomG6SensorSessionRequest(code)))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerSetDexcomG7PairingCodeRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("SetDexcomG7PairingCodeRequest")
    val input = EditText(context)
    input.hint = context.getString(R.string.debug_pairing_code)
    input.inputType = InputType.TYPE_CLASS_NUMBER
    builder.setView(input)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(SetDexcomG7PairingCodeRequest(input.text.toString().toInt())))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerFillCannulaRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("FillCannulaRequest")
    val input = EditText(context)
    input.hint = context.getString(R.string.debug_prime_size_milliunits)
    input.inputType = InputType.TYPE_CLASS_NUMBER
    builder.setView(input)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(FillCannulaRequest(input.text.toString().toInt())))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerChangeTimeDateRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("ChangeTimeDateRequest")
    val input = EditText(context)
    input.hint = context.getString(R.string.debug_tandem_epoch_seconds)
    input.inputType = InputType.TYPE_CLASS_NUMBER
    builder.setView(input)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(ChangeTimeDateRequest(input.text.toString().toLong())))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerSetActiveIDPRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("SetActiveIDPRequest")
    val input = EditText(context)
    input.hint = context.getString(R.string.debug_idp_id)
    input.inputType = InputType.TYPE_CLASS_NUMBER
    builder.setView(input)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(SetActiveIDPRequest(input.text.toString().toInt())))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerCreateIDPRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("CreateIDPRequest")
    val layout = LinearLayout(context)
    layout.orientation = LinearLayout.VERTICAL
    val nameInput = EditText(context)
    nameInput.hint = context.getString(R.string.debug_profile_name)
    val carbRatioInput = EditText(context)
    carbRatioInput.hint = context.getString(R.string.debug_first_segment_carb_ratio)
    carbRatioInput.inputType = InputType.TYPE_CLASS_NUMBER
    val basalRateInput = EditText(context)
    basalRateInput.hint = context.getString(R.string.debug_first_segment_basal_rate)
    basalRateInput.inputType = InputType.TYPE_CLASS_NUMBER
    val targetBgInput = EditText(context)
    targetBgInput.hint = context.getString(R.string.debug_first_segment_target_bg)
    targetBgInput.inputType = InputType.TYPE_CLASS_NUMBER
    val isfInput = EditText(context)
    isfInput.hint = context.getString(R.string.debug_first_segment_isf)
    isfInput.inputType = InputType.TYPE_CLASS_NUMBER
    val durationInput = EditText(context)
    durationInput.hint = context.getString(R.string.debug_insulin_duration)
    durationInput.inputType = InputType.TYPE_CLASS_NUMBER
    val carbEntryInput = EditText(context)
    carbEntryInput.hint = context.getString(R.string.debug_carb_entry)
    carbEntryInput.inputType = InputType.TYPE_CLASS_NUMBER
    val sourceIdInput = EditText(context)
    sourceIdInput.hint = context.getString(R.string.debug_source_idp_id_blank_for_new)
    listOf(
        nameInput,
        carbRatioInput,
        basalRateInput,
        targetBgInput,
        isfInput,
        durationInput,
        carbEntryInput,
        sourceIdInput,
    ).forEach { layout.addView(it) }
    builder.setView(layout)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        val message = if (sourceIdInput.text.isNullOrBlank()) {
            CreateIDPRequest(
                nameInput.text.toString(),
                carbRatioInput.text.toString().toInt(),
                basalRateInput.text.toString().toInt(),
                targetBgInput.text.toString().toInt(),
                isfInput.text.toString().toInt(),
                durationInput.text.toString().toInt(),
                carbEntryInput.text.toString().toInt(),
            )
        } else {
            CreateIDPRequest(
                nameInput.text.toString(),
                sourceIdInput.text.toString().toInt(),
            )
        }
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(message))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerRenameIDPRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("RenameIDPRequest")
    val layout = LinearLayout(context)
    layout.orientation = LinearLayout.VERTICAL
    val idInput = EditText(context)
    idInput.hint = context.getString(R.string.debug_idp_id)
    idInput.inputType = InputType.TYPE_CLASS_NUMBER
    val nameInput = EditText(context)
    nameInput.hint = context.getString(R.string.debug_new_profile_name)
    listOf(idInput, nameInput).forEach { layout.addView(it) }
    builder.setView(layout)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(RenameIDPRequest(idInput.text.toString().toInt(), nameInput.text.toString())))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerDeleteIDPRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("DeleteIDPRequest")
    val input = EditText(context)
    input.hint = context.getString(R.string.debug_idp_id)
    input.inputType = InputType.TYPE_CLASS_NUMBER
    builder.setView(input)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(DeleteIDPRequest(input.text.toString().toInt())))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerSetIDPSettingsRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("SetIDPSettingsRequest")
    val layout = LinearLayout(context)
    layout.orientation = LinearLayout.VERTICAL
    val idInput = EditText(context)
    idInput.hint = context.getString(R.string.debug_idp_id)
    idInput.inputType = InputType.TYPE_CLASS_NUMBER
    val insulinDurationInput = EditText(context)
    insulinDurationInput.hint = context.getString(R.string.debug_insulin_duration)
    insulinDurationInput.inputType = InputType.TYPE_CLASS_NUMBER
    val carbEntryInput = EditText(context)
    carbEntryInput.hint = context.getString(R.string.debug_carb_entry)
    carbEntryInput.inputType = InputType.TYPE_CLASS_NUMBER
    val changeTypeInput = EditText(context)
    changeTypeInput.hint = context.getString(R.string.debug_change_type_insulin_or_carb_entry)
    listOf(idInput, insulinDurationInput, carbEntryInput, changeTypeInput).forEach { layout.addView(it) }
    builder.setView(layout)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        val changeType = SetIDPSettingsRequest.ChangeType.valueOf(changeTypeInput.text.toString().uppercase())
        val message = SetIDPSettingsRequest(
            idInput.text.toString().toInt(),
            insulinDurationInput.text.toString().toInt(),
            carbEntryInput.text.toString().toInt(),
            changeType,
        )
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(message))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerSetIDPSegmentRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("SetIDPSegmentRequest")
    val layout = LinearLayout(context)
    layout.orientation = LinearLayout.VERTICAL
    val idpIdInput = EditText(context)
    idpIdInput.hint = context.getString(R.string.debug_idp_id)
    idpIdInput.inputType = InputType.TYPE_CLASS_NUMBER
    val unknownIdInput = EditText(context)
    unknownIdInput.hint = context.getString(R.string.debug_unknown_id)
    unknownIdInput.inputType = InputType.TYPE_CLASS_NUMBER
    val segmentIndexInput = EditText(context)
    segmentIndexInput.hint = context.getString(R.string.debug_segment_index)
    segmentIndexInput.inputType = InputType.TYPE_CLASS_NUMBER
    val operationInput = EditText(context)
    operationInput.hint = context.getString(R.string.debug_operation_modify_create_delete_segment)
    val startTimeInput = EditText(context)
    startTimeInput.hint = context.getString(R.string.debug_profile_start_time)
    startTimeInput.inputType = InputType.TYPE_CLASS_NUMBER
    val basalRateInput = EditText(context)
    basalRateInput.hint = context.getString(R.string.debug_profile_basal_rate)
    basalRateInput.inputType = InputType.TYPE_CLASS_NUMBER
    val carbRatioInput = EditText(context)
    carbRatioInput.hint = context.getString(R.string.debug_profile_carb_ratio)
    carbRatioInput.inputType = InputType.TYPE_CLASS_NUMBER
    val targetBgInput = EditText(context)
    targetBgInput.hint = context.getString(R.string.debug_profile_target_bg)
    targetBgInput.inputType = InputType.TYPE_CLASS_NUMBER
    val isfInput = EditText(context)
    isfInput.hint = context.getString(R.string.debug_profile_isf)
    isfInput.inputType = InputType.TYPE_CLASS_NUMBER
    val statusInput = EditText(context)
    statusInput.hint = context.getString(R.string.debug_status_bitmask)
    statusInput.inputType = InputType.TYPE_CLASS_NUMBER
    listOf(
        idpIdInput,
        unknownIdInput,
        segmentIndexInput,
        operationInput,
        startTimeInput,
        basalRateInput,
        carbRatioInput,
        targetBgInput,
        isfInput,
        statusInput,
    ).forEach { layout.addView(it) }
    builder.setView(layout)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        val message = SetIDPSegmentRequest(
            idpIdInput.text.toString().toInt(),
            unknownIdInput.text.toString().toInt(),
            segmentIndexInput.text.toString().toInt(),
            SetIDPSegmentRequest.IDPSegmentOperation.valueOf(operationInput.text.toString().uppercase()),
            startTimeInput.text.toString().toInt(),
            basalRateInput.text.toString().toInt(),
            carbRatioInput.text.toString().toLong(),
            targetBgInput.text.toString().toInt(),
            isfInput.text.toString().toInt(),
            statusInput.text.toString().toInt(),
        )
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(message))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerSetMaxBolusLimitRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("SetMaxBolusLimitRequest")
    val input = EditText(context)
    input.hint = context.getString(R.string.debug_max_bolus_milliunits)
    input.inputType = InputType.TYPE_CLASS_NUMBER
    builder.setView(input)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(SetMaxBolusLimitRequest(input.text.toString().toInt())))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerSetMaxBasalLimitRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("SetMaxBasalLimitRequest")
    val input = EditText(context)
    input.hint = context.getString(R.string.debug_max_hourly_basal_milliunits)
    input.inputType = InputType.TYPE_CLASS_NUMBER
    builder.setView(input)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(SetMaxBasalLimitRequest(input.text.toString().toLong())))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerSetPumpSoundsRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("SetPumpSoundsRequest")
    val layout = LinearLayout(context)
    layout.orientation = LinearLayout.VERTICAL
    val quickBolusInput = EditText(context)
    quickBolusInput.hint = context.getString(R.string.debug_quick_bolus_annunciation)
    quickBolusInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
    val generalInput = EditText(context)
    generalInput.hint = context.getString(R.string.debug_general_annunciation)
    generalInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
    val reminderInput = EditText(context)
    reminderInput.hint = context.getString(R.string.debug_remind_annunciation)
    reminderInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
    val alertInput = EditText(context)
    alertInput.hint = context.getString(R.string.debug_alert_annunciation)
    alertInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
    val alarmInput = EditText(context)
    alarmInput.hint = context.getString(R.string.debug_alarm_annunciation)
    alarmInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
    val cgmAInput = EditText(context)
    cgmAInput.hint = context.getString(R.string.debug_cgm_alert_annunciation)
    cgmAInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
    val cgmBInput = EditText(context)
    cgmBInput.hint = context.getString(R.string.debug_cgm_alert_annunciation_b)
    cgmBInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
    val changeBitmaskInput = EditText(context)
    changeBitmaskInput.hint = context.getString(R.string.debug_change_bitmask)
    changeBitmaskInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
    listOf(
        quickBolusInput,
        generalInput,
        reminderInput,
        alertInput,
        alarmInput,
        cgmAInput,
        cgmBInput,
        changeBitmaskInput,
    ).forEach { layout.addView(it) }
    builder.setView(layout)
//    builder.setNeutralButton("RunTest") { dialog, _ ->
//        dialog.dismiss()
//        val message = {r: Int -> SetPumpSoundsRequest(
//            r,
//            3,
//            r,
//            3,
//            3,
//            3,
//            0,
//            0,
//            4,
//        )}
//        sendPumpCommands(SendType.DEBUG_PROMPT, (-127..128).flatMap { i -> listOf(message(i)) } )
//    }
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        // 空输入此前会抛 NumberFormatException；调试弹窗统一按 0 处理
        val message = SetPumpSoundsRequest(
            quickBolusInput.text.toString().toIntOrNull() ?: 0,
            generalInput.text.toString().toIntOrNull() ?: 0,
            reminderInput.text.toString().toIntOrNull() ?: 0,
            alertInput.text.toString().toIntOrNull() ?: 0,
            alarmInput.text.toString().toIntOrNull() ?: 0,
            cgmAInput.text.toString().toIntOrNull() ?: 0,
            cgmBInput.text.toString().toIntOrNull() ?: 0,
            changeBitmaskInput.text.toString().toIntOrNull() ?: 0,
        )
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(message))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun triggerSetPumpAlertSnoozeRequestMessage(
    context: Context,
    sendPumpCommands: (SendType, List<Message>) -> Unit,
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle("SetPumpAlertSnoozeRequest")
    val layout = LinearLayout(context)
    layout.orientation = LinearLayout.VERTICAL
    val enabledInput = EditText(context)
    enabledInput.hint = context.getString(R.string.debug_snooze_enabled_true_false_1_0)
    val durationInput = EditText(context)
    durationInput.hint = context.getString(R.string.debug_snooze_duration_minutes)
    durationInput.inputType = InputType.TYPE_CLASS_NUMBER
    listOf(enabledInput, durationInput).forEach { layout.addView(it) }
    builder.setView(layout)
    builder.setPositiveButton(context.getString(R.string.debug_send)) { dialog, _ ->
        val message = SetPumpAlertSnoozeRequest(
            parseBoolInput(enabledInput.text.toString()),
            durationInput.text.toString().toInt(),
        )
        sendPumpCommands(SendType.DEBUG_PROMPT, listOf(message))
        dialog.dismiss()
    }
    builder.setNegativeButton(context.getString(R.string.debug_cancel)) { dialog, _ -> dialog.cancel() }
    builder.show()
}

fun messageCacheToJson(cache: List<Pair<Message, Instant>>): String {
    val arr = JSONArray()
    cache.forEach {
        arr.put(JSONObject(messagePairToJson(it)))
    }
    return arr.toString()
}

fun messagePairToJson(it: Pair<Message, Instant>): String {
    val obj = JSONObject()
    obj.put("message", it.first.javaClass.name)
    obj.put("props", messagePropsToJson(it.first.props()))
    obj.put("fields", JSONObject(JavaHelpers.autoToStringJson(it.first, setOf())))
    obj.put("time", it.second)
    obj.put("timeEpoch", it.second.epochSecond)
    return obj.toString()
}

fun messagePropsToJson(props: MessageProps): JSONObject? {
    val raw = props.toString().removePrefix("@com.jwoglom.pumpx2.pump.messages.annotations.MessageProps(").removeSuffix(")")
    val spl = Splitter.on(", ")
            .withKeyValueSeparator('=')
            .split(raw) as Map<*, *>?
    return spl?.let { JSONObject(it) }
}

fun shortPumpMessageTitle(message: Any): String {
    return message.javaClass.name.replace(
        "com.jwoglom.pumpx2.pump.messages.response.",
        ""
    )
}

fun shortHistoryLogPumpMessageTitle(message: HistoryLog): String {
    val title = message.javaClass.name.replace(
        "com.jwoglom.pumpx2.pump.messages.response.historyLog.",
        ""
    )
    if (title == "UnknownHistoryLog") {
        return "${title}_${message.typeId()}"
    }
    return title
}

fun shortPumpMessageDetail(message: Message): String {
    return message.toString().removePrefix("${message.javaClass.simpleName}[").removeSuffix("]")
}

fun verbosePumpMessage(message: Message): String {
    return message.verboseToString().replace("com.jwoglom.pumpx2.pump.messages.", "")
}

fun historyLogCacheToJson(cache: Map<Long, HistoryLog>): String {
    val arr = JSONArray()
    cache.entries.sortedBy {
        it.key
    }.forEach {
        arr.put(JSONObject(historyLogToJson(it)))
    }
    return arr.toString()
}

fun historyLogMessagePropsToJson(props: HistoryLogProps?): JSONObject? {
    if (props == null) {
        return JSONObject()
    }
    val raw = props.toString().removePrefix("@com.jwoglom.pumpx2.pump.messages.annotations.HistoryLogProps(").removeSuffix(")")
    val spl = Splitter.on(", ")
        .withKeyValueSeparator('=')
        .split(raw) as Map<*, *>?
    return spl?.let { JSONObject(it) }
}

fun historyLogToJson(it: Map.Entry<Long, HistoryLog>): String {
    val obj = JSONObject()
    obj.put("historyLog", it.value.javaClass.name)
    obj.put("props", historyLogMessagePropsToJson(it.value.props()))
    obj.put("fields", JSONObject(JavaHelpers.autoToStringJson(it.value, setOf())))
    obj.put("sequenceNum", it.key)
    obj.put("pumpTimeSec", it.value.pumpTimeSec)
    obj.put("pumpTime", it.value.pumpTimeSecInstant)
    return obj.toString()
}

@Preview(showBackground = true)
@Composable
internal fun DebugDefaultPreview() {
    ControlX2Theme() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            Debug(
                sendMessage = { _, _ -> },
                sendPumpCommands = { _, _ -> },
            )
        }
    }
}
