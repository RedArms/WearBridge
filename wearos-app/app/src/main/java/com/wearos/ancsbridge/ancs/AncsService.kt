package com.wearos.ancsbridge.ancs

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.graphics.Typeface
import android.os.IBinder
import android.os.Vibrator
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.wearos.ancsbridge.AncsApplication
import com.wearos.ancsbridge.R
import com.wearos.ancsbridge.alarm.ClockAlarm
import com.wearos.ancsbridge.alarm.WakeAlarm
import com.wearos.ancsbridge.ble.AncsConstants
import com.wearos.ancsbridge.ble.BleConnectionManager
import com.wearos.ancsbridge.ble.BleScanner
import com.wearos.ancsbridge.ble.BondStateReceiver
import com.wearos.ancsbridge.health.HealthMonitoring
import com.wearos.ancsbridge.health.HealthSyncManager
import com.wearos.ancsbridge.model.AncsEvent
import com.wearos.ancsbridge.model.AncsNotification
import com.wearos.ancsbridge.model.ConnectionState
import com.wearos.ancsbridge.ui.IncomingCallActivity
import com.wearos.ancsbridge.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that manages the BLE connection to iPhone and ANCS notification pipeline.
 *
 * Pipeline: BLE scan → connect → bond → subscribe → receive NS events →
 *           fetch attributes via CP → parse DS response → post Android notification
 */
@SuppressLint("MissingPermission")
class AncsService : Service() {

    companion object {
        private const val TAG = "AncsService"
        private const val SERVICE_NOTIFICATION_ID = 1
        private const val NOTIFICATION_ID_BASE = 1000

        const val ACTION_START = "com.wearos.ancsbridge.START"
        const val ACTION_STOP = "com.wearos.ancsbridge.STOP"
        const val ACTION_CONNECT = "com.wearos.ancsbridge.CONNECT"
        const val ACTION_DISCONNECT = "com.wearos.ancsbridge.DISCONNECT"
        const val ACTION_RECONNECT = "com.wearos.ancsbridge.RECONNECT"
        const val ACTION_PERFORM_NOTIFICATION_ACTION = "com.wearos.ancsbridge.PERFORM_ACTION"
        const val ACTION_SEND_QUICK_REPLY = "com.wearos.ancsbridge.QUICK_REPLY"
        const val ACTION_CLEAR_ALL = "com.wearos.ancsbridge.CLEAR_ALL"
        const val ACTION_STOP_WAKE_ALARM = "com.wearos.ancsbridge.STOP_WAKE_ALARM"
        const val EXTRA_SNOOZE = "snooze"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_ACTION_ID = "action_id"
        const val EXTRA_QUICK_REPLY_TEXT = "quick_reply_text"

        /** Stops the wake-up alarm from the watch (and the iPhone's, when ANCS lets us). */
        fun stopWakeAlarmIntent(context: android.content.Context, snooze: Boolean): Intent =
            Intent(context, AncsService::class.java).apply {
                action = ACTION_STOP_WAKE_ALARM
                putExtra(EXTRA_SNOOZE, snooze)
            }
        const val NOTIFICATION_ID_CALL = 999
        private const val MAX_ACTIVE_NOTIFICATIONS = 20 // Android limit is 25; keep headroom
        // How long one Control Point request may wait for its Data Source response.
        private const val DATA_SOURCE_TIMEOUT_MS = 2_000L

        // GATT writes fail while another operation (e.g. a health sync write) is
        // in flight, so retry instead of dropping the request: ~6 s, twice the
        // health write timeout.
        private const val GATT_WRITE_ATTEMPTS = 40
        private const val GATT_RETRY_DELAY_MS = 150L

        // Health sync stays off the link while a call rings and right after ANCS activity.
        private const val HEALTH_SYNC_QUIET_MS = 3_000L
        // A call whose end we never heard of (link dropped) stops blocking health sync after this.
        private const val CALL_BLOCKS_HEALTH_SYNC_MS = 3 * 60_000L

        /** Shared connection state observable by the ViewModel */
        private val _sharedConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        val sharedConnectionState: StateFlow<ConnectionState> = _sharedConnectionState.asStateFlow()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var connectionManager: BleConnectionManager
    private lateinit var scanner: BleScanner
    private lateinit var notificationManager: NotificationManager
    private lateinit var vibrator: Vibrator
    private lateinit var healthSync: HealthSyncManager

    private val dataSourceAssembler = DataSourceAssembler()
    /** Completed when the Data Source response to the current request is complete. */
    private var pendingResponse: CompletableDeferred<Unit>? = null
    private lateinit var appNames: AppNameStore

    // Track active notification IDs in posting order (oldest first) for auto-cleanup
    private val activeNotificationIds = ArrayDeque<Int>()

    // Vibration cooldown — don't vibrate more than once every 3 seconds

    // Reconnection grace period — suppress vibration for notifications arriving
    // in the first 60 seconds after subscribing (they're backlog, not truly new)
    private var subscriptionTime = 0L
    @Volatile private var activeCallUid: Long = -1
    @Volatile private var lastAncsActivityMs = 0L
    @Volatile private var callStartedMs = 0L
    /** The ringing iPhone Clock alarm, when ANCS delivers it (not all iPhones do). */
    @Volatile private var clockAlarm: ClockAlarm? = null
    private val reconnectionGracePeriodMs = 60_000L

    // Serial queue for Control Point requests (one Data Source response at a time)
    private val attributeRequestQueue = Channel<ControlPointRequest>(Channel.BUFFERED)
    private var requestProcessorJob: Job? = null

    private sealed interface ControlPointRequest

    private data class AttributeRequest(
        val event: AncsEvent,
        val requestedAttributes: List<Int>
    ) : ControlPointRequest

    /** Asks the iPhone for an app's display name (once per app, see AppNameStore). */
    private data class AppNameRequest(val appIdentifier: String) : ControlPointRequest

    override fun onCreate() {
        super.onCreate()
        connectionManager = BleConnectionManager(this)
        scanner = BleScanner(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        vibrator = getSystemService(Vibrator::class.java)
        appNames = AppNameStore(this)

        // Register bond state receiver
        registerReceiver(connectionManager.bondStateReceiver, BondStateReceiver.intentFilter)

        // Register PhoneAccount for Telecom framework integration
        // This lets us show incoming calls via the system call UI
        AncsConnectionService.registerPhoneAccount(this)

        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification("Starting..."))

        // Listen for Notification Source events
        scope.launch {
            connectionManager.notificationSourceEvents.collect { data ->
                handleNotificationSourceEvent(data)
            }
        }

        // Listen for Data Source events
        scope.launch {
            connectionManager.dataSourceEvents.collect { data ->
                handleDataSourceEvent(data)
            }
        }

        // Listen for companion app call state events (Live Call Status feature)
        scope.launch {
            connectionManager.callStateEvents.collect { data ->
                handleCompanionCallState(data)
            }
        }

        scope.launch {
            connectionManager.wakeAlarmEvents.collect { data ->
                handleCompanionWakeAlarm(data)
            }
        }

        // Update service notification and shared state based on connection state
        scope.launch {
            connectionManager.connectionState.collectLatest { state ->
                // Publish to shared flow so ViewModel can observe
                _sharedConnectionState.value = state

                val text = when (state) {
                    is ConnectionState.Idle -> "Idle"
                    is ConnectionState.Scanning -> "Scanning..."
                    is ConnectionState.Connecting -> "Connecting to ${state.deviceName ?: "iPhone"}..."
                    is ConnectionState.Bonding -> "Pairing with ${state.deviceName ?: "iPhone"}..."
                    is ConnectionState.Connected -> {
                        subscriptionTime = System.currentTimeMillis()
                        "Connected to ${state.deviceName ?: "iPhone"}"
                    }
                    is ConnectionState.Disconnected -> "Disconnected"
                    is ConnectionState.Error -> "Error: ${state.message}"
                }
                notificationManager.notify(SERVICE_NOTIFICATION_ID, buildServiceNotification(text))
            }
        }

        // Start the attribute request processor
        startRequestProcessor()

        // Health Data Bridge: send passive health data to the iPhone (opt-in from
        // the watch UI). Health Services forgets registrations on reboot, so
        // re-register whenever the service starts.
        healthSync = HealthSyncManager(this, connectionManager, scope) {
            val now = System.currentTimeMillis()
            val callRinging = activeCallUid != -1L && now - callStartedMs < CALL_BLOCKS_HEALTH_SYNC_MS
            !callRinging && now - lastAncsActivityMs > HEALTH_SYNC_QUIET_MS
        }
        healthSync.start()
        scope.launch { HealthMonitoring.registerIfEnabled(this@AncsService) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, null -> {
                // Try to reconnect to bonded iPhone
                tryReconnectBonded()
            }
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (address != null) {
                    val device = scanner.let {
                        val manager = getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
                        manager.adapter.getRemoteDevice(address)
                    }
                    connectionManager.connect(device)
                }
            }
            ACTION_DISCONNECT -> {
                connectionManager.disconnect()
            }
            ACTION_RECONNECT -> {
                tryReconnectBonded()
            }
            ACTION_PERFORM_NOTIFICATION_ACTION -> {
                val uid = intent.getLongExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_UID, -1)
                val actionId = intent.getIntExtra(EXTRA_ACTION_ID, -1)
                if (uid != -1L && actionId != -1) {
                    performNotificationAction(uid, actionId)
                }
            }
            ACTION_SEND_QUICK_REPLY -> {
                val replyText = intent.getStringExtra(EXTRA_QUICK_REPLY_TEXT) ?: return START_STICKY
                val callerName = intent.getStringExtra("caller_name") ?: "Unknown"
                val payload = "$callerName|$replyText"
                Log.i(TAG, "Sending quick reply via BLE: $payload")
                scope.launch {
                    if (!retryGattWrite { connectionManager.writeQuickReply(payload) }) {
                        Log.e(TAG, "Failed to send quick reply")
                    }
                }
            }
            ACTION_STOP_WAKE_ALARM -> {
                WakeAlarm.stop(this)
                // Stop (or snooze) the iPhone alarm too, if ANCS gave us its actions.
                clockAlarm?.let { alarm ->
                    val snooze = intent.getBooleanExtra(EXTRA_SNOOZE, false)
                    val actionId = if (snooze) alarm.snoozeActionId ?: alarm.stopActionId else alarm.stopActionId
                    if (actionId != null) performNotificationAction(alarm.uid, actionId)
                }
            }
            ACTION_CLEAR_ALL -> {
                Log.i(TAG, "Clearing all notifications (${activeNotificationIds.size} tracked)")
                activeNotificationIds.forEach { id ->
                    notificationManager.cancel(id)
                }
                activeNotificationIds.clear()
                notificationManager.cancel(NOTIFICATION_ID_CALL)
            }
            ACTION_STOP -> {
                connectionManager.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(connectionManager.bondStateReceiver)
        } catch (_: IllegalArgumentException) { }
        healthSync.stop()
        connectionManager.destroy()
        attributeRequestQueue.close()
        scope.cancel()
    }

    private fun tryReconnectBonded() {
        val device = connectionManager.getBondedIPhone()
        if (device != null) {
            Log.i(TAG, "Found bonded device: ${device.name ?: device.address}")
            connectionManager.reconnect(device)
        } else {
            Log.i(TAG, "No bonded iPhone found")
        }
    }

    private fun handleNotificationSourceEvent(data: ByteArray) {
        lastAncsActivityMs = System.currentTimeMillis()
        val event = NotificationSourceParser.parse(data) ?: run {
            Log.w(TAG, "Failed to parse NS event")
            return
        }

        Log.d(TAG, "NS event: id=${event.eventId} cat=${event.categoryId} uid=${event.notificationUid}")

        when {
            event.isAdded || event.isModified -> {
                // Skip pre-existing notifications at the NS level — don't even
                // fetch attributes. This avoids GATT writes that trigger system haptics.
                if (event.isPreExisting) {
                    Log.d(TAG, "Skipping pre-existing NS event uid=${event.notificationUid}")
                    return
                }

                // Also skip during grace period (non-flagged backlog)
                val now = System.currentTimeMillis()
                if ((now - subscriptionTime) < reconnectionGracePeriodMs && !event.isIncomingCall) {
                    Log.d(TAG, "Skipping NS event during grace period uid=${event.notificationUid}")
                    return
                }

                // MODIFIED on active call = call was answered on iPhone.
                // Transition the call overlay to "Active on iPhone" state.
                if (event.isModified && event.isIncomingCall && activeCallUid == event.notificationUid) {
                    Log.i(TAG, "Call MODIFIED (answered on iPhone) uid=${event.notificationUid}")
                    vibrator.cancel()
                    // Cancel the CallStyle notification — no longer ringing
                    notificationManager.cancel(NOTIFICATION_ID_CALL)
                    // Tell the activity to transition to "active call" UI
                    sendBroadcast(Intent(IncomingCallActivity.ACTION_CALL_ANSWERED).apply {
                        setPackage(packageName)
                    })
                    return
                }

                if (event.isIncomingCall) {
                    // IMMEDIATELY show call screen — don't wait for attribute fetch.
                    // The NS payload already tells us it's a call. We'll update
                    // the caller name later if attributes arrive in time.
                    Log.i(TAG, "Incoming call NS event (cat=1) uid=${event.notificationUid} — showing call screen NOW")
                    showIncomingCall(AncsNotification(
                        uid = event.notificationUid,
                        categoryId = event.categoryId,
                        eventFlags = event.eventFlags,
                        appIdentifier = "com.apple.mobilephone",
                        title = "Incoming Call",
                        subtitle = null,
                        message = "",
                        date = null,
                        appDisplayName = "Phone",
                        positiveActionLabel = "Answer",
                        negativeActionLabel = "Decline"
                    ))
                }

                // Build list of attributes to request
                val attrs = mutableListOf(
                    AncsConstants.ATTR_APP_IDENTIFIER,
                    AncsConstants.ATTR_TITLE,
                    AncsConstants.ATTR_SUBTITLE,
                    AncsConstants.ATTR_MESSAGE,
                    AncsConstants.ATTR_DATE
                )
                if (event.hasPositiveAction) attrs.add(AncsConstants.ATTR_POSITIVE_ACTION_LABEL)
                if (event.hasNegativeAction) attrs.add(AncsConstants.ATTR_NEGATIVE_ACTION_LABEL)

                // Enqueue attribute fetch (for all notifications including calls)
                scope.launch {
                    attributeRequestQueue.send(AttributeRequest(event, attrs))
                }
            }
            event.isRemoved -> {
                // The iPhone alarm was stopped or snoozed there: stop the watch alarm too.
                if (clockAlarm?.uid == event.notificationUid) {
                    clockAlarm = null
                    WakeAlarm.stop(this)
                }

                // Cancel the Android notification and remove from tracking
                val removedId = notificationIdForUid(event.notificationUid)
                notificationManager.cancel(removedId)
                activeNotificationIds.remove(removedId)

                // If this was an incoming call, dismiss everything
                if (event.isIncomingCall || activeCallUid == event.notificationUid) {
                    Log.i(TAG, "Call ended (REMOVE) uid=${event.notificationUid}")
                    activeCallUid = -1
                    vibrator.cancel()
                    notificationManager.cancel(NOTIFICATION_ID_CALL)
                    AncsConnectionService.endActiveCall()
                    sendBroadcast(Intent(IncomingCallActivity.ACTION_CALL_ENDED).apply {
                        setPackage(packageName)
                    })
                }
            }
        }
    }

    /**
     * Handle call state pushed from the iOS companion app via BLE.
     * This is the "Live Call Status" feature — more reliable than ANCS MODIFIED events.
     * Values: 0x00=idle, 0x01=ringing, 0x02=active (answered), 0x03=ended
     */
    private fun handleCompanionCallState(data: ByteArray) {
        if (data.isEmpty()) return
        val state = data[0]
        Log.i(TAG, "Companion call state: ${when (state) {
            AncsConstants.CALL_STATE_RINGING -> "ringing"
            AncsConstants.CALL_STATE_ACTIVE -> "active"
            AncsConstants.CALL_STATE_ENDED -> "ended"
            else -> "idle"
        }}")

        when (state) {
            AncsConstants.CALL_STATE_ACTIVE -> {
                // Call was answered on iPhone — transition overlay
                if (activeCallUid != -1L) {
                    Log.i(TAG, "Companion: call answered on iPhone, transitioning overlay")
                    vibrator.cancel()
                    notificationManager.cancel(NOTIFICATION_ID_CALL)
                    sendBroadcast(Intent(IncomingCallActivity.ACTION_CALL_ANSWERED).apply {
                        setPackage(packageName)
                    })
                }
            }
            AncsConstants.CALL_STATE_ENDED -> {
                // Call ended on iPhone — dismiss everything
                if (activeCallUid != -1L) {
                    Log.i(TAG, "Companion: call ended on iPhone, dismissing overlay")
                    activeCallUid = -1
                    vibrator.cancel()
                    notificationManager.cancel(NOTIFICATION_ID_CALL)
                    AncsConnectionService.endActiveCall()
                    sendBroadcast(Intent(IncomingCallActivity.ACTION_CALL_ENDED).apply {
                        setPackage(packageName)
                    })
                }
            }
        }
    }

    private fun handleDataSourceEvent(data: ByteArray) {
        lastAncsActivityMs = System.currentTimeMillis()
        val result = dataSourceAssembler.onData(data)
        if (result != null) pendingResponse?.complete(Unit)
        when (result) {
            is DataSourceAssembler.Result.Notification -> {
                val notification = result.notification
                Log.i(TAG, "Notification complete: ${notification.title} - ${notification.message}")
                scope.launch {
                    postNotification(notification)
                }
            }
            is DataSourceAssembler.Result.AppAttributes -> {
                Log.i(TAG, "App name: ${result.appIdentifier} = \"${result.displayName}\"")
                appNames.save(result.appIdentifier, result.displayName)
            }
            null -> Unit
        }
    }

    private fun startRequestProcessor() {
        requestProcessorJob = scope.launch {
            for (request in attributeRequestQueue) {
                when (request) {
                    is AttributeRequest -> processAttributeRequest(request)
                    is AppNameRequest -> processAppNameRequest(request)
                }
            }
        }
    }

    private suspend fun processAttributeRequest(request: AttributeRequest) {
        val event = request.event
        lastAncsActivityMs = System.currentTimeMillis() // keeps health sync off while the queue drains

        // Prepare the assembler to expect this response
        dataSourceAssembler.expectNotificationAttributes(
            uid = event.notificationUid,
            requestedAttributes = request.requestedAttributes,
            category = event.categoryId,
            flags = event.eventFlags
        )

        // Write the Control Point request
        val cpData = ControlPointWriter.buildGetNotificationAttributes(
            uid = event.notificationUid,
            hasPositiveAction = event.hasPositiveAction,
            hasNegativeAction = event.hasNegativeAction
        )

        pendingResponse = CompletableDeferred()
        val success = writeControlPointWithRetry(cpData)
        if (!success) {
            Log.e(TAG, "Failed to write Control Point for uid=${event.notificationUid}")
            dataSourceAssembler.reset()
            pendingResponse = null
            return
        }

        // The response comes through dataSourceEvents -> handleDataSourceEvent.
        awaitDataSourceResponse()
    }

    /** One request at a time: the next one only goes out once this response is complete. */
    private suspend fun awaitDataSourceResponse() {
        val response = pendingResponse ?: return
        if (withTimeoutOrNull(DATA_SOURCE_TIMEOUT_MS) { response.await() } == null) {
            Log.w(TAG, "No complete Data Source response in time")
            dataSourceAssembler.reset()
        }
        pendingResponse = null
    }

    private suspend fun processAppNameRequest(request: AppNameRequest) {
        dataSourceAssembler.expectAppAttributes(request.appIdentifier)
        val cpData = ControlPointWriter.buildGetAppAttributes(request.appIdentifier)
        lastAncsActivityMs = System.currentTimeMillis()
        pendingResponse = CompletableDeferred()
        if (!writeControlPointWithRetry(cpData)) {
            Log.w(TAG, "Failed to request the app name of ${request.appIdentifier}")
            dataSourceAssembler.reset()
            pendingResponse = null
            return
        }
        awaitDataSourceResponse()
    }

    private suspend fun postNotification(notification: AncsNotification) {
        val isPreExisting = notification.eventFlags and AncsConstants.EVENT_FLAG_PRE_EXISTING != 0

        Log.d(TAG, "postNotification: uid=${notification.uid} cat=${notification.categoryId} " +
            "flags=0x${notification.eventFlags.toString(16)} app=${notification.appIdentifier} " +
            "title=${notification.title} preExisting=$isPreExisting")

        // Resolve app display name if not cached
        val appName = resolveAppName(notification.appIdentifier)
        val updatedNotification = notification.copy(appDisplayName = appName)

        // Detect incoming calls by category OR by message content
        // WhatsApp sends calls as cat=4 (Social) with "☎" or "Incoming" in message
        // Regular phone may send as cat=1 (IncomingCall) or cat=2 with "Incoming" content
        val callApps = setOf(
            "net.whatsapp.WhatsApp", "net.whatsapp.WhatsAppSMB",
            "com.apple.mobilephone", "com.apple.facetime"
        )
        val messageAndTitle = "${notification.message} ${notification.title}"
        val hasCallIndicator = messageAndTitle.contains("Incoming Call", ignoreCase = true) ||
            messageAndTitle.contains("Incoming Voice", ignoreCase = true) ||
            messageAndTitle.contains("Incoming Video", ignoreCase = true) ||
            messageAndTitle.contains("Incoming Audio", ignoreCase = true) ||
            messageAndTitle.contains("☎") ||
            messageAndTitle.contains("Audio Call", ignoreCase = true) ||
            messageAndTitle.contains("Video Call", ignoreCase = true) ||
            messageAndTitle.contains("Voice Call", ignoreCase = true) ||
            messageAndTitle.contains("is calling", ignoreCase = true) ||
            messageAndTitle.contains("Ringing", ignoreCase = true)
        val isIncomingCall = notification.categoryId == AncsConstants.CATEGORY_INCOMING_CALL ||
            (notification.positiveActionLabel != null && hasCallIndicator) ||
            (notification.appIdentifier in callApps && hasCallIndicator)

        if (isIncomingCall) {
            Log.i(TAG, "Showing incoming call screen for ${notification.title}")
            showIncomingCall(updatedNotification)
            return
        }

        // Skip pre-existing notifications — they're already on the phone.
        if (isPreExisting) {
            Log.d(TAG, "Skipping pre-existing notification uid=${notification.uid}")
            return
        }

        // A ringing iPhone Clock alarm becomes the watch's gentle wake-up alarm.
        ClockAlarm.from(
            notification.uid, notification.appIdentifier,
            notification.positiveActionLabel, notification.negativeActionLabel
        )?.let { alarm ->
            Log.i(TAG, "iPhone Clock alarm uid=${alarm.uid} " +
                "(${alarm.positiveActionLabel} / ${alarm.negativeActionLabel})")
            clockAlarm = alarm
            WakeAlarm.start(this, snoozeAvailable = alarm.snoozeActionId != null)
            return
        }

        // Grace period and pre-existing checks are now done at the NS event level
        // (handleNotificationSourceEvent) so we never even fetch attributes for those.

        // Route alarm/timer apps to the schedule channel regardless of ANCS category
        val alarmApps = setOf("com.apple.mobiletimer", "com.apple.reminders", "com.apple.mobilecal")
        val channelId = if (notification.appIdentifier in alarmApps) {
            AncsApplication.CHANNEL_SCHEDULE
        } else {
            AncsApplication.channelForCategory(notification.categoryId)
        }
        val iconResId = AppIconMapper.getIconResId(notification.appIdentifier, notification.categoryId)
        Log.d(TAG, "Icon mapping: app='${notification.appIdentifier}' cat=${notification.categoryId} -> resId=$iconResId (default=${R.drawable.ic_notification_default})")
        val notifId = notificationIdForUid(notification.uid)

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // Look: the sender (or the app, for app alerts) with a colored initials
        // avatar, the app's accent color on the icon, and the app name as subtext.
        val displayAppName = appName ?: notification.appIdentifier
        val look = NotificationLook.content(updatedNotification, displayAppName)
        val accent = NotificationLook.accentColor(notification.appIdentifier, notification.categoryId)
        val avatarColor = if (look.senderIsApp) accent else NotificationLook.avatarColor(look.senderName)
        val sender = Person.Builder()
            .setName(look.senderName)
            .setKey("${notification.appIdentifier}/${look.senderName}")
            .setIcon(NotificationAvatars.icon(look.senderName, avatarColor))
            .build()
        val text = messageText(look, displayAppName)
        val notifTime = parseAncsDate(notification.date)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(iconResId)
            .setColor(accent)
            .setContentTitle(look.conversationTitle ?: look.senderName)
            .setContentText(text)
            .setSubText(displayAppName)
            .setTicker("${look.senderName}: $text")
            .setWhen(notifTime)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidCategory(notification.categoryId))
            // Each notification gets a unique group key to prevent Android's auto-grouping.
            // Auto-grouping adds SILENT flag via GROUP_ALERT_SUMMARY which kills
            // heads-up overlays, vibration, and sound on ALL child notifications.
            .setGroup("ancs_${notification.uid}")
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
            .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)

        // MessagingStyle for everything — Samsung One UI Watch only shows the body
        // in the heads-up overlay for MessagingStyle (gotcha 14).
        val self = Person.Builder()
            .setName(getString(R.string.notification_self))
            .setKey("self")
            .build()
        val style = NotificationCompat.MessagingStyle(self)
            .addMessage(text, notifTime, sender)
        look.conversationTitle?.let { group ->
            style.setConversationTitle(group).setGroupConversation(true)
        }
        builder.setStyle(style)

        // Add action buttons if available
        if (notification.positiveActionLabel != null) {
            val positiveIntent = PendingIntent.getBroadcast(
                this, notifId,
                Intent(NotificationActionReceiver.ACTION_POSITIVE).apply {
                    setPackage(packageName)
                    putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_UID, notification.uid)
                },
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_check, notification.positiveActionLabel, positiveIntent)
        }

        if (notification.negativeActionLabel != null) {
            val negativeIntent = PendingIntent.getBroadcast(
                this, notifId + 500_000,
                Intent(NotificationActionReceiver.ACTION_NEGATIVE).apply {
                    setPackage(packageName)
                    putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_UID, notification.uid)
                },
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_close, notification.negativeActionLabel, negativeIntent)
        }

        // Never call setNotificationSilent() — it suppresses the heads-up overlay
        // on Wear OS. Let the channel handle sound/vibration. We handle vibration
        // manually via the Vibrator API with cooldown, so no double-buzz.

        // Auto-cleanup: evict oldest notifications if approaching the system cap
        evictOldestIfNeeded()

        notificationManager.notify(notifId, builder.build())
        activeNotificationIds.addLast(notifId)

        // Learn the app's real (localized) name for the next notifications.
        if (appNames.shouldRequest(notification.appIdentifier)) {
            attributeRequestQueue.send(AppNameRequest(notification.appIdentifier))
        }
    }

    /** The message line: an emphasized headline (subject or alert title) above the body. */
    private fun messageText(look: NotificationLook.Content, appName: String): CharSequence {
        val headline = look.headline
        val body = look.body
        return when {
            headline == null -> body.ifEmpty { appName }
            body.isEmpty() -> headline
            else -> SpannableStringBuilder()
                .append(headline, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                .append('\n')
                .append(body)
        }
    }

    /**
     * Evict the oldest notifications to stay under the system limit.
     * Keeps MAX_ACTIVE_NOTIFICATIONS slots, leaving room for service + call notifications.
     */
    private fun evictOldestIfNeeded() {
        while (activeNotificationIds.size >= MAX_ACTIVE_NOTIFICATIONS) {
            val oldId = activeNotificationIds.removeFirst()
            notificationManager.cancel(oldId)
            Log.d(TAG, "Auto-evicted oldest notification id=$oldId (${activeNotificationIds.size} active)")
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun showIncomingCall(notification: AncsNotification) {
        val callerName = notification.title.ifEmpty { "Incoming Call" }
        val appName = notification.appDisplayName ?: "Phone"

        // Track active call UID to avoid duplicate showIncomingCall calls
        if (activeCallUid == notification.uid) {
            // Already showing — just update caller name if it changed
            if (callerName != "Incoming Call") {
                AncsConnectionService.activeConnection?.updateCallerName(callerName)
                // Also update the IncomingCallActivity via broadcast
                sendBroadcast(Intent(IncomingCallActivity.ACTION_CALLER_NAME_UPDATED).apply {
                    setPackage(packageName)
                    putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerName)
                })
                Log.i(TAG, "Updated caller name to '$callerName' for uid=${notification.uid}")
            }
            return
        }
        activeCallUid = notification.uid
        callStartedMs = System.currentTimeMillis()

        // 1. Report to Telecom framework — self-managed connection allows
        //    our foreground service to launch activities while ringing
        AncsConnectionService.reportIncomingCall(
            this, notification.uid, callerName, appName
        )

        // 2. Directly launch call activity — the Telecom self-managed connection
        //    grants us the background activity start exception
        val callIntent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerName)
            putExtra(IncomingCallActivity.EXTRA_NOTIFICATION_UID, notification.uid)
            putExtra(IncomingCallActivity.EXTRA_APP_NAME, appName)
        }
        try {
            startActivity(callIntent)
            Log.i(TAG, "Started IncomingCallActivity directly for '$callerName'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start IncomingCallActivity: ${e.message}")
        }

        // 3. Post CallStyle notification as fallback
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val answerPendingIntent = PendingIntent.getActivity(
            this, 1, callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val declinePendingIntent = PendingIntent.getBroadcast(
            this, 2,
            Intent(NotificationActionReceiver.ACTION_NEGATIVE).apply {
                setPackage(packageName)
                putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_UID, notification.uid)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val caller = androidx.core.app.Person.Builder()
            .setName(callerName)
            .setImportant(true)
            .build()
        val callNotification = NotificationCompat.Builder(this, AncsApplication.CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_phone)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller, declinePendingIntent, answerPendingIntent
                )
            )
            .build()
        notificationManager.notify(NOTIFICATION_ID_CALL, callNotification)
        Log.i(TAG, "Posted CallStyle notification for '$callerName'")
    }


    private suspend fun writeControlPointWithRetry(data: ByteArray): Boolean =
        retryGattWrite { connectionManager.writeControlPoint(data) }

    /** Retries a GATT write that returns false while another operation is in flight. */
    private suspend fun retryGattWrite(write: () -> Boolean): Boolean {
        repeat(GATT_WRITE_ATTEMPTS) { attempt ->
            if (write()) return true
            if (attempt < GATT_WRITE_ATTEMPTS - 1) delay(GATT_RETRY_DELAY_MS)
        }
        return false
    }

    /** The companion app says the iPhone's wake-up alarm rings (0x01) or stopped (0x00). */
    private fun handleCompanionWakeAlarm(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0]) {
            AncsConstants.WAKE_ALARM_RINGING -> {
                Log.i(TAG, "Companion: iPhone wake-up alarm ringing")
                WakeAlarm.start(this, snoozeAvailable = clockAlarm?.snoozeActionId != null)
            }
            AncsConstants.WAKE_ALARM_STOPPED -> {
                Log.i(TAG, "Companion: iPhone wake-up alarm stopped")
                WakeAlarm.stop(this)
            }
        }
    }

    private fun performNotificationAction(uid: Long, actionId: Int) {
        val data = ControlPointWriter.buildPerformNotificationAction(uid, actionId)
        scope.launch {
            if (!writeControlPointWithRetry(data)) {
                Log.e(TAG, "Failed to write notification action for uid=$uid")
            }
        }
        // Cancel vibration (in case this is a call answer/decline)
        if (!WakeAlarm.isRinging) vibrator.cancel()
        // Cancel the Android notification and remove from tracking
        val notifId = notificationIdForUid(uid)
        notificationManager.cancel(notifId)
        activeNotificationIds.remove(notifId)
    }

    private fun resolveAppName(appIdentifier: String): String? =
        if (appIdentifier.isEmpty()) null else appNames.displayName(appIdentifier)

    private fun buildNotificationText(notification: AncsNotification): String {
        return buildString {
            if (notification.title.isNotEmpty()) {
                append(notification.title)
            }
            if (!notification.subtitle.isNullOrEmpty()) {
                if (isNotEmpty()) append(": ")
                append(notification.subtitle)
            }
            if (notification.message.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append(notification.message)
            }
        }
    }

    private fun parseAncsDate(date: String?): Long {
        if (date == null) return System.currentTimeMillis()
        return try {
            val sdf = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US)
            sdf.parse(date)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun androidCategory(categoryId: Int): String = when (categoryId) {
        AncsConstants.CATEGORY_INCOMING_CALL -> Notification.CATEGORY_CALL
        AncsConstants.CATEGORY_MISSED_CALL -> Notification.CATEGORY_MISSED_CALL
        AncsConstants.CATEGORY_VOICEMAIL -> Notification.CATEGORY_VOICEMAIL
        AncsConstants.CATEGORY_SOCIAL -> Notification.CATEGORY_SOCIAL
        AncsConstants.CATEGORY_SCHEDULE -> Notification.CATEGORY_EVENT
        AncsConstants.CATEGORY_EMAIL -> Notification.CATEGORY_EMAIL
        else -> Notification.CATEGORY_MESSAGE
    }

    private fun notificationIdForUid(uid: Long): Int =
        (NOTIFICATION_ID_BASE + (uid % 500_000)).toInt()

    private fun buildServiceNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, AncsApplication.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification_default)
            .setContentTitle("WearBridge")
            .setContentText(text)
            .setContentIntent(intent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
