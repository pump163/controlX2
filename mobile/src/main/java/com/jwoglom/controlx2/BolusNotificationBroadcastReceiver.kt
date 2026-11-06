package com.jwoglom.controlx2

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.jwoglom.controlx2.pump.confirmBolusRequestBaseNotification
import com.jwoglom.controlx2.messaging.MessageBusFactory
import com.jwoglom.controlx2.shared.InitiateConfirmedBolusSerializer
import com.jwoglom.controlx2.shared.messaging.MessageBus
import com.jwoglom.controlx2.shared.messaging.MessageBusSender
import com.jwoglom.controlx2.shared.PumpMessageSerializer
import com.jwoglom.controlx2.shared.MessagePaths
import com.jwoglom.controlx2.shared.util.shortTimeAgo
import com.jwoglom.controlx2.shared.util.twoDecimalPlaces
import com.jwoglom.pumpx2.pump.PumpState
import com.jwoglom.pumpx2.pump.messages.models.InsulinUnit
import com.jwoglom.pumpx2.pump.messages.request.control.CancelBolusRequest
import com.jwoglom.pumpx2.pump.messages.request.control.InitiateBolusRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.CurrentBolusStatusRequest
import com.jwoglom.pumpx2.pump.messages.response.control.InitiateBolusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse
import com.jwoglom.pumpx2.shared.Hex
import com.jwoglom.controlx2.Prefs
import timber.log.Timber
import java.time.Instant
import com.jwoglom.controlx2.R


class BolusNotificationBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Timber.i("BolusNotificationBroadcastReceiver $context $intent")

        val messageBus = MessageBusFactory.createMessageBus(context!!)

        val action = intent?.getStringExtra("action")
        val notifId = getCurrentNotificationId(context)
        when (action) {
            "INITIATE" -> {
                CommService.cancelAutoApproveStatic(context)
                validateBolusInfo(intent, context)?.let { intentRequest ->
                    Timber.w("BolusNotificationBroadcastReceiver performing $intentRequest")
                    val secretKey = prefs(context)?.getString("initiateBolusSecret", "") ?: ""

                    val rawBytes = InitiateConfirmedBolusSerializer.toBytes(
                        secretKey,
                        intentRequest
                    )

                    Timber.i(
                        "BolusNotificationBroadcastReceiver initiate-confirmed-bolus bytes: ${
                            String(
                                rawBytes
                            )
                        }"
                    )

                    val bolusSource = prefs(context)?.getString("initiateBolusSource", "") ?: ""
                    if (bolusSource == "wear") {
                        sendMessageWhenReady(messageBus, MessagePaths.TO_CLIENT_INITIATE_CONFIRMED_BOLUS, rawBytes, context)
                    } else if (bolusSource == "phone") {
                        sendMessageWhenReady(messageBus, MessagePaths.TO_SERVER_INITIATE_CONFIRMED_BOLUS, rawBytes, context)
                    }
                    if (!PumpState.actionsAffectingInsulinDeliveryEnabled()) {
                        // The same message will appear on the wearable
                        reply(
                            context, notifId, confirmBolusRequestBaseNotification(
                                context,
                                context?.getString(R.string.bolus_not_enabled) ?: "",
                                context?.getString(R.string.bolus_was_requested_set_not_enabled) ?: ""
                            )
                        )
                        return
                    }

                    val cancelIntent =
                        Intent(context, BolusNotificationBroadcastReceiver::class.java).apply {
                            putExtra("action", "CANCEL")
                            putExtra("bolusId", intentRequest.bolusID)
                        }
                    val cancelPendingIntent = PendingIntent.getBroadcast(
                        context,
                        2003,
                        cancelIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                    )

                    reply(
                        context, notifId, confirmBolusRequestBaseNotification(
                            context,
                            context?.getString(R.string.bolus_requesting_bolus) ?: "",
                            "${bolusSummaryText(intentRequest)} " +
                                    context?.getString(R.string.bolus_will_be_delivered) ?: ""
                        ).addAction(R.drawable.decline, context?.getString(R.string.bolus_cancel) ?: "", cancelPendingIntent)
                    )
                }
            }
            "INITIATE_RESPONSE" -> {
                getCurrentBolusToConfirm(context)?.let { initiateRequest ->
                    getInitiateBolusResponse(intent)?.let { initiateResponse ->
                        if (initiateResponse.status != 0 || initiateResponse.statusType != InitiateBolusResponse.BolusResponseStatus.SUCCESS) {
                            Timber.e("Bolus was not initiated. $initiateRequest $initiateResponse")
                            reply(
                                context,
                                notifId,
                                confirmBolusRequestBaseNotification(
                                    context,
                                    context?.getString(R.string.bolus_rejected_by_pump) ?: "",
                                    context?.getString(R.string.bolus_pump_reported_could_not_initiate_bolus,
                                        "${initiateResponse.statusType ?: "UNKNOWN"}(${initiateResponse.statusTypeId})") ?: ""
                                )
                            )
                            resetBolusPrefs(context)
                            return
                        }

                        if (initiateRequest.bolusID != initiateResponse.bolusId) {
                            Timber.e("Sanity error. bolus id does not match: $initiateRequest $initiateResponse")
                            reply(
                                context,
                                notifId,
                                confirmBolusRequestBaseNotification(
                                    context,
                                    context?.getString(R.string.bolus_sanity_check_error) ?: "",
                                    context?.getString(R.string.bolus_check_pump_to_delivered) ?: ""
                                )
                            )
                            resetBolusPrefs(context)
                            return
                        }

                        val cancelIntent =
                            Intent(context, BolusNotificationBroadcastReceiver::class.java).apply {
                                putExtra("action", "CANCEL")
                                putExtra("bolusId", initiateResponse.bolusId)
                            }
                        val cancelPendingIntent = PendingIntent.getBroadcast(
                            context,
                            2003,
                            cancelIntent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                        )

                        reply(
                            context, notifId, confirmBolusRequestBaseNotification(
                                context,
                                context?.getString(R.string.bolus_requested) ?: "",
                                context?.getString(R.string.bolus_request_is_being_prepared, bolusSummaryText(initiateRequest)) ?: ""
                            ).addAction(R.drawable.decline, context?.getString(R.string.bolus_cancel) ?: "", cancelPendingIntent)
                        )
                        
                        // Save initial status and bolusId
                        prefs(context)?.edit()
                            ?.putString("lastBolusStatus", CurrentBolusStatusResponse.CurrentBolusStatus.REQUESTING.name)
                            ?.putInt("lastBolusId", initiateResponse.bolusId)
                            ?.apply()
                        
                        // Start periodic status updates
                        startBolusStatusUpdates(context, messageBus, initiateResponse.bolusId, initiateRequest)
                    }
                }
            }
            "STATUS_UPDATE" -> {
                getCurrentBolusToConfirm(context)?.let { initiateRequest ->
                    getCurrentBolusStatusResponse(intent)?.let { statusResponse ->
                        // Only update notification if status has changed
                        val previousStatus = prefs(context)?.getString("lastBolusStatus", null)
                        val currentStatus = statusResponse.status.name
                        val previousBolusId = prefs(context)?.getInt("lastBolusId", -1) ?: -1
                        val currentBolusId = statusResponse.bolusId
                        
                        // Update if status changed OR if bolusId changed (especially when it becomes 0 for completion)
                        val statusChanged = previousStatus != currentStatus
                        val bolusIdChanged = previousBolusId != currentBolusId
                        val bolusCompleted = currentBolusId == 0 && previousBolusId != 0
                        
                        if (statusChanged || bolusIdChanged) {
                            Timber.d("BolusNotificationBroadcastReceiver status changed: $previousStatus -> $currentStatus, bolusId: $previousBolusId -> $currentBolusId")
                            
                            // Save the new status and bolusId
                            prefs(context)?.edit()
                                ?.putString("lastBolusStatus", currentStatus)
                                ?.putInt("lastBolusId", currentBolusId)
                                ?.apply()
                            
                            val notifId = getCurrentNotificationId(context)
                            
                            // Determine status text - prioritize completion detection
                            val statusText = when {
                                bolusCompleted -> context?.getString(R.string.bolus_was_completed) ?: ""
                                currentBolusId == 0 -> context?.getString(R.string.bolus_was_completed) ?: ""
                                statusResponse.status == CurrentBolusStatusResponse.CurrentBolusStatus.REQUESTING -> context?.getString(R.string.bolus_is_being_prepared_2) ?: ""
                                statusResponse.status == CurrentBolusStatusResponse.CurrentBolusStatus.DELIVERING -> context?.getString(R.string.bolus_is_being_delivered) ?: ""
                                else -> context?.getString(R.string.bolus_was_completed) ?: ""
                            }
                            
                            // Only show cancel button if bolus is still active
                            val notificationBuilder = confirmBolusRequestBaseNotification(
                                context,
                                context?.getString(R.string.bolus_initiated) ?: "",
                                context?.getString(R.string.bolus_status_text, bolusSummaryText(initiateRequest), statusText) ?: ""
                            )
                            
                            if (bolusCompleted || currentBolusId == 0) {
                                val dismissIntent =
                                    Intent(context, BolusNotificationBroadcastReceiver::class.java).apply {
                                        putExtra("action", "DISMISS")
                                    }
                                val dismissPendingIntent = PendingIntent.getBroadcast(
                                    context,
                                    2004,
                                    dismissIntent,
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                                )
                                notificationBuilder.addAction(R.drawable.confirm, context?.getString(R.string.bolus_done) ?: "", dismissPendingIntent)
                            } else {
                                val cancelIntent =
                                    Intent(context, BolusNotificationBroadcastReceiver::class.java).apply {
                                        putExtra("action", "CANCEL")
                                        putExtra("bolusId", currentBolusId)
                                    }
                                val cancelPendingIntent = PendingIntent.getBroadcast(
                                    context,
                                    2003,
                                    cancelIntent,
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                                )
                                notificationBuilder.addAction(R.drawable.decline, context?.getString(R.string.bolus_cancel) ?: "", cancelPendingIntent)
                            }
                            
                            reply(context, notifId, notificationBuilder)
                        } else {
                            Timber.d("BolusNotificationBroadcastReceiver status unchanged: $currentStatus, bolusId: $currentBolusId, skipping notification update")
                        }
                        
                        // Stop updates if bolus is complete (bolusId becomes 0 or status is complete)
                        if (bolusCompleted || (currentBolusId == 0 && previousBolusId != 0) || 
                            (statusResponse.status != CurrentBolusStatusResponse.CurrentBolusStatus.REQUESTING && 
                             statusResponse.status != CurrentBolusStatusResponse.CurrentBolusStatus.DELIVERING && 
                             currentBolusId == 0)) {
                            stopBolusStatusUpdates(context)
                            // Clear the saved status after a delay to allow the completion notification to be shown
                            Handler(Looper.getMainLooper()).postDelayed({
                                prefs(context)?.edit()
                                    ?.remove("lastBolusStatus")
                                    ?.remove("lastBolusId")
                                    ?.apply()
                            }, 1000)
                        }
                    }
                }
            }
            "REJECT" -> {
                CommService.cancelAutoApproveStatic(context)
                stopBolusStatusUpdates(context)
                reply(
                    context, notifId, confirmBolusRequestBaseNotification(
                        context,
                        context?.getString(R.string.bolus_rejected_by_phone) ?: "",
                        context?.getString(R.string.bolus_will_not_be_completed) ?: ""
                    )
                )
                resetBolusPrefs(context)
                sendMessageWhenReady(messageBus, MessagePaths.TO_CLIENT_BOLUS_REJECTED, "from_phone".toByteArray(), context)
            }
            "DISMISS" -> {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(notifId)
                resetBolusPrefs(context)
            }
            "CANCEL" -> {
                stopBolusStatusUpdates(context)
                val bolusId = intent.getIntExtra("bolusId", 0)

                // Send cancellation command - retry logic with delays to ensure MessageBus is ready
                // Note: Multiple sends are intentional to increase delivery probability
                val cancelBytes = PumpMessageSerializer.toBytes(CancelBolusRequest(bolusId))
                repeat (5) { attempt ->
                    sendMessageWhenReady(
                        messageBus,
                        MessagePaths.TO_PUMP_COMMAND,
                        cancelBytes,
                        context,
                        delayMs = attempt * 100L // Stagger retries: 0ms, 100ms, 200ms, 300ms, 400ms
                    )
                }
            }
            else -> {
                Timber.w("BolusNotificationBroadcastReceiver request invalid: $action ${intent?.extras}")
                reply(
                    context,
                    notifId,
                    confirmBolusRequestBaseNotification(
                        context,
                        context?.getString(R.string.bolus_receiver_request_invalid) ?: "",
                        context?.getString(R.string.bolus_will_not_be_delivered) ?: ""
                    )
                )
                resetBolusPrefs(context)
            }
        }
    }

    private fun validateBolusInfo(intent: Intent?, context: Context?): InitiateBolusRequest? {
        val intentRequestBytes = intent?.getByteArrayExtra("request")
        val notifId = getCurrentNotificationId(context)
        if (intentRequestBytes == null) {
            Timber.w("BolusNotificationBroadcastReceiver invalid intent: $intentRequestBytes ${intent?.extras}")
            reply(
                context,
                notifId,
                confirmBolusRequestBaseNotification(context, context?.getString(R.string.bolus_error) ?: "", context?.getString(R.string.bolus_invalid_intent) ?: "")
            )
            resetBolusPrefs(context)
            return null
        }

        val currentBolusToConfirm = getCurrentBolusToConfirm(context)

        val intentRequest = PumpMessageSerializer.fromBytes(intentRequestBytes)
        val expired = checkBolusTimeExpired(context)
        if (expired != null) {
            Timber.e("Bolus expired: $intentRequest")
            reply(
                context,
                notifId,
                confirmBolusRequestBaseNotification(
                    context,
                    context?.getString(R.string.bolus_request_expired) ?: "",
                    context?.getString(R.string.bolus_request_expired_boluses_time_out, shortTimeAgo(Instant.now().plusMillis(expired))) ?: ""
                )
            )
            resetBolusPrefs(context)
            return null
        }

        if (currentBolusToConfirm == null) {
            Timber.e("No current bolus to confirm, bolus request invalid: $intentRequest")
            reply(
                context,
                notifId,
                confirmBolusRequestBaseNotification(
                    context,
                    context?.getString(R.string.bolus_request_invalid) ?: "",
                    context?.getString(R.string.bolus_request_was_invalid_intent, intentRequest) ?: ""
                )
            )
            resetBolusPrefs(context)
            return null
        }

        if (!intentRequest.cargo.contentEquals(currentBolusToConfirm.cargo) || intentRequest !is InitiateBolusRequest) {
            Timber.e("BolusNotificationBroadcastReceiver mismatched intent intentRequest=$intentRequest currentBolusToConfirm=$currentBolusToConfirm ${intentRequest.cargo} != ${currentBolusToConfirm.cargo}")
            reply(
                context,
                notifId,
                confirmBolusRequestBaseNotification(context, context?.getString(R.string.bolus_error) ?: "", context?.getString(R.string.bolus_mismatched_intent) ?: "")
            )
            resetBolusPrefs(context)
            return null
        }


        return intentRequest
    }

    private fun bolusSummaryText(intentRequest: InitiateBolusRequest): String {
        return "${
            twoDecimalPlaces(
                InsulinUnit.from1000To1(intentRequest.totalVolume)
            )
        }u bolus"
    }

    private fun getCurrentBolusToConfirm(context: Context?): InitiateBolusRequest? {
        val prefBytes = Hex.decodeHex(prefs(context)?.getString("initiateBolusRequest", ""))
        if (prefBytes == null || prefBytes.isEmpty()) {
            return null
        }

        val prefRequest = PumpMessageSerializer.fromBytes(prefBytes)
        if (prefRequest !is InitiateBolusRequest) {
            return null
        }

        return prefRequest
    }

    private fun checkBolusTimeExpired(context: Context?): Long? {
        val initiateMillis = prefs(context)?.getLong("initiateBolusTime", 0L) ?: return -1

        val nowMillis = Instant.now().toEpochMilli()

        // Bolus expires after 1 minute, or after auto-approve timeout + 30s buffer
        val autoApproveTimeout = Prefs(context!!).wearBolusAutoApproveTimeoutSeconds()
        val expiryMs = if (autoApproveTimeout > 0) {
            (autoApproveTimeout + 30) * 1000L
        } else {
            60 * 1000L
        }
        if (nowMillis - initiateMillis > expiryMs) {
            return nowMillis - initiateMillis
        }

        return null
    }

    private fun getInitiateBolusResponse(intent: Intent?): InitiateBolusResponse? {
        val intentResponseBytes = intent?.getByteArrayExtra("response")
        if (intentResponseBytes == null) {
            Timber.w("BolusNotificationBroadcastReceiver invalid response intent")
            return null
        }

        return PumpMessageSerializer.fromBytes(intentResponseBytes) as InitiateBolusResponse
    }

    private fun getCurrentBolusStatusResponse(intent: Intent?): CurrentBolusStatusResponse? {
        val intentStatusBytes = intent?.getByteArrayExtra("status")
        if (intentStatusBytes == null) {
            Timber.w("BolusNotificationBroadcastReceiver invalid status intent")
            return null
        }

        return PumpMessageSerializer.fromBytes(intentStatusBytes) as CurrentBolusStatusResponse
    }

    private fun resetBolusPrefs(context: Context?) {
        prefs(context)?.edit()
            ?.remove("initiateBolusRequest")
            ?.remove("initiateBolusTime")
            ?.remove("lastBolusStatus")
            ?.remove("lastBolusId")
            ?.apply()
    }

    private fun getCurrentNotificationId(context: Context?): Int {
        return prefs(context)?.getInt("initiateBolusNotificationId", 100) ?: 0
    }

    private fun reply(
        context: Context?,
        bolusNotificationId: Int,
        builder: NotificationCompat.Builder
    ) {
        cancelNotif(context, bolusNotificationId)
        makeNotif(context, bolusNotificationId, builder.build())
    }

    private fun makeNotif(context: Context?, id: Int, notif: Notification) {
        val notificationManager =
            context?.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;
        notificationManager.notify(id, notif)
    }

    private fun cancelNotif(context: Context?, id: Int) {
        val notificationManager =
            context?.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;
        notificationManager.cancel(id)
    }

    private fun prefs(context: Context?): SharedPreferences? {
        return context?.getSharedPreferences("WearX2", Context.MODE_PRIVATE)
    }

    private var statusUpdateHandler: Handler? = null
    private var statusUpdateRunnable: Runnable? = null

    /**
     * Start periodic status updates for an active bolus
     */
    private fun startBolusStatusUpdates(
        context: Context,
        messageBus: MessageBus,
        bolusId: Int,
        initiateRequest: InitiateBolusRequest
    ) {
        stopBolusStatusUpdates(context) // Stop any existing updates
        
        val handler = Handler(Looper.getMainLooper())
        statusUpdateHandler = handler
        
        var requestCount = 0
        val maxRequests = 60 // Request for up to 60 seconds (60 requests at 1 second intervals)
        
        val runnable = object : Runnable {
            override fun run() {
                if (requestCount < maxRequests) {
                    Timber.d("BolusNotificationBroadcastReceiver requesting bolus status update (request $requestCount)")
                    sendMessageWhenReady(
                        messageBus,
                        MessagePaths.TO_PUMP_COMMANDS_BUST_CACHE,
                        PumpMessageSerializer.toBulkBytes(listOf(CurrentBolusStatusRequest())),
                        context,
                        delayMs = 0
                    )
                    requestCount++
                    handler.postDelayed(this, 1000) // Request every second
                } else {
                    Timber.d("BolusNotificationBroadcastReceiver stopping status updates after $maxRequests requests")
                    stopBolusStatusUpdates(context)
                }
            }
        }
        
        statusUpdateRunnable = runnable
        // Start first request after a short delay
        handler.postDelayed(runnable, 500)
    }

    /**
     * Stop periodic status updates
     */
    private fun stopBolusStatusUpdates(context: Context) {
        statusUpdateRunnable?.let { runnable ->
            statusUpdateHandler?.removeCallbacks(runnable)
            statusUpdateRunnable = null
            statusUpdateHandler = null
            Timber.d("BolusNotificationBroadcastReceiver stopped status updates")
        }
    }

    /**
     * Send a message through the MessageBus, ensuring the bus is ready first.
     * Uses a Handler-based approach with a small delay to allow MessageBus initialization,
     * similar to the original waitForApiClient() but non-blocking.
     * 
     * @param messageBus The MessageBus instance to use
     * @param path The message path
     * @param data The message data
     * @param context Android context
     * @param delayMs Optional delay before sending (for staggered retries)
     */
    private fun sendMessageWhenReady(
        messageBus: MessageBus,
        path: String,
        data: ByteArray,
        context: Context,
        delayMs: Long = 0
    ) {
        val handler = Handler(Looper.getMainLooper())
        val totalDelay = delayMs + 100L // Add 100ms base delay to ensure MessageBus is initialized
        
        handler.postDelayed({
            Timber.d("BolusNotificationBroadcastReceiver sending message: $path")
            messageBus.sendMessage(path, data, MessageBusSender.MOBILE_UI)
            Timber.d("BolusNotificationBroadcastReceiver message queued: $path")
        }, totalDelay)
    }

}