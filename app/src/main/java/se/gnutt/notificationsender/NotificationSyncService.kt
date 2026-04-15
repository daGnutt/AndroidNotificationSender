package se.gnutt.notificationsender

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

class NotificationSyncService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationSync"
        const val ACTION_REFRESH = "se.gnutt.notificationsender.REFRESH_NOTIFICATIONS"
        private const val ICON_SIZE = 96

        // Maps common action name aliases (including emoji) to Android semantic action integers.
        // See Notification.Action.SEMANTIC_ACTION_* constants.
        private val SEMANTIC_ACTION_ALIASES = mapOf(
            "like"         to 8,  // SEMANTIC_ACTION_THUMBS_UP
            "thumbs up"    to 8,
            "👍"           to 8,
            "dislike"      to 9,  // SEMANTIC_ACTION_THUMBS_DOWN
            "thumbs down"  to 9,
            "👎"           to 9,
            "reply"        to 1,  // SEMANTIC_ACTION_REPLY
            "mark as read" to 2,  // SEMANTIC_ACTION_MARK_AS_READ
            "read"         to 2,
            "archive"      to 5,  // SEMANTIC_ACTION_ARCHIVE
            "mute"         to 6,  // SEMANTIC_ACTION_MUTE
        )
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var settings: SettingsManager
    private lateinit var apiClient: ApiClient

    // Serialises concurrent onNotificationPosted calls for the same notification key,
    // preventing race conditions that create duplicate server entries.
    private val keyMutexes = ConcurrentHashMap<String, Mutex>()
    private fun mutexFor(key: String) = keyMutexes.computeIfAbsent(key) { Mutex() }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Manual refresh requested")
            scope.launch { fullSync() }
        }
    }

    private val fcmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                FcmService.ACTION_FCM_DISMISS -> {
                    val serverId = intent.getStringExtra(FcmService.EXTRA_SERVER_ID) ?: return
                    val notificationKey = settings.getNotificationKeyByServerId(serverId) ?: return
                    Log.d(TAG, "FCM dismiss for server entry $serverId")
                    settings.removeNotificationMapping(notificationKey)
                    try { cancelNotification(notificationKey) } catch (e: Exception) {
                        Log.e(TAG, "Failed to cancel notification: ${e.message}")
                    }
                }
                FcmService.ACTION_FCM_ACTION -> {
                    val serverId = intent.getStringExtra(FcmService.EXTRA_SERVER_ID) ?: return
                    val actionTaken = intent.getStringExtra(FcmService.EXTRA_ACTION_TAKEN) ?: return
                    val notificationKey = settings.getNotificationKeyByServerId(serverId) ?: return
                    val actionResponse = intent.getStringExtra(FcmService.EXTRA_ACTION_RESPONSE)
                    Log.d(TAG, "FCM action '$actionTaken' for server entry $serverId")
                    scope.launch { handleActionRequest(notificationKey, serverId, actionTaken, actionResponse) }
                }
                FcmService.ACTION_FCM_RESYNC -> {
                    Log.i(TAG, "FCM resync received — triggering full sync")
                    scope.launch { fullSync() }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        apiClient = ApiClient()
        registerReceiver(refreshReceiver, IntentFilter(ACTION_REFRESH), RECEIVER_NOT_EXPORTED)
        val fcmFilter = IntentFilter().apply {
            addAction(FcmService.ACTION_FCM_DISMISS)
            addAction(FcmService.ACTION_FCM_ACTION)
            addAction(FcmService.ACTION_FCM_RESYNC)
        }
        registerReceiver(fcmReceiver, fcmFilter, RECEIVER_NOT_EXPORTED)
        Log.i(TAG, "NotificationSyncService started")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(refreshReceiver)
        unregisterReceiver(fcmReceiver)
        job.cancel()
        Log.i(TAG, "NotificationSyncService stopped")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (!settings.isConfigured) return
        Log.i(TAG, "Listener connected — syncing active notifications")
        scope.launch { fullSync() }
        scope.launch { pollServerDismissals() }
        registerFcmToken()
    }

    private fun registerFcmToken() {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            scope.launch {
                val error = apiClient.registerFcmToken(settings.endpoint, settings.userId, token)
                if (error == null) {
                    settings.fcmToken = token
                    Log.i(TAG, "FCM token registered with server")
                } else {
                    Log.e(TAG, "Failed to register FCM token: $error")
                }
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to get FCM token: ${e.message}")
        }
    }

    private suspend fun fullSync() {
        val active = try { activeNotifications } catch (e: Exception) { null } ?: return

        // Delete all locally-known server entries
        val localMappings = settings.getAllMappings()
        for ((notificationKey, serverId) in localMappings) {
            try {
                apiClient.deleteNotification(settings.endpoint, settings.userId, serverId)
            } catch (_: Exception) {}
            settings.removeNotificationMapping(notificationKey)
        }

        // Purge any remaining server entries — this catches both entries whose delete
        // failed above (network error) and true orphans from a previous crash where
        // storeNotificationMapping never ran.
        val serverNotifications = apiClient.getNotifications(settings.endpoint, settings.userId)
        if (serverNotifications != null) {
            for (serverNotif in serverNotifications) {
                try {
                    apiClient.deleteNotification(settings.endpoint, settings.userId, serverNotif.id)
                    Log.d(TAG, "Purged server entry ${serverNotif.id}")
                } catch (_: Exception) {}
            }
        }

        // Re-snapshot active notifications immediately before posting to avoid a race
        // where a notification in the earlier snapshot has since been dismissed: in that
        // case onNotificationRemoved would see no mapping and exit early, leaving the
        // freshly-posted server entry orphaned.
        val currentActiveKeys = try { activeNotifications?.map { it.key }?.toSet() } catch (_: Exception) { null }

        // Post all currently active notifications
        for (sbn in active) {
            if (sbn.packageName == packageName) continue
            if (currentActiveKeys != null && sbn.key !in currentActiveKeys) continue
            postSbn(sbn)
        }
        Log.i(TAG, "Full sync complete — posted ${active.count { it.packageName != packageName }} notifications")
    }

    private suspend fun pollServerDismissals() {
        // When FCM is active (token registered), poll infrequently as a safety net for dropped
        // messages (e.g. device was offline). When FCM is unavailable, poll every 10 s so that
        // server-side dismissals and actions still reach the device in near-real-time.
        var lastFcmActive: Boolean? = null
        while (scope.isActive) {
            val fcmActive = settings.fcmToken != null
            if (fcmActive != lastFcmActive) {
                lastFcmActive = fcmActive
                if (fcmActive) {
                    Log.i(TAG, "FCM is active — switching poll loop to 5-minute safety-net interval")
                } else {
                    Log.i(TAG, "FCM unavailable — polling every 10 s for server dismissals")
                }
            }
            delay(if (fcmActive) 300_000L else 10_000L)
            if (!settings.isConfigured) continue

            val serverNotifications = apiClient.getNotifications(settings.endpoint, settings.userId) ?: continue
            val localMappings = settings.getAllMappings()
            val activeKeys = try { activeNotifications?.map { it.key }?.toSet() } catch (_: Exception) { null }

            // Detect server restart: the server holds notifications in memory only, so a
            // restart wipes all entries.  If the server returns an empty list but we still
            // have local mappings, assume a restart rather than mass-dismissal and resync
            // (re-post everything) instead of cancelling the phone notifications.
            if (serverNotifications.isEmpty() && localMappings.isNotEmpty()) {
                Log.i(TAG, "Server returned empty list with ${localMappings.size} local mappings — server may have restarted, resyncing")
                fullSync()
                continue
            }

            for ((notificationKey, serverId) in localMappings) {
                val serverNotif = serverNotifications.find { it.id == serverId }

                when {
                    serverNotif == null -> {
                        Log.d(TAG, "Fallback poll: server dismissed $serverId — cancelling local notification")
                        settings.removeNotificationMapping(notificationKey)
                        try { cancelNotification(notificationKey) } catch (e: Exception) {
                            Log.e(TAG, "Failed to cancel notification: ${e.message}")
                        }
                    }

                    serverNotif.actionTaken != null && !serverNotif.actionDispatched -> {
                        Log.d(TAG, "Fallback poll: action '${serverNotif.actionTaken}' requested for $serverId")
                        handleActionRequest(notificationKey, serverId, serverNotif.actionTaken, serverNotif.actionResponse)
                    }

                    // Phone is the source of truth: if the notification is no longer active
                    // on the device, delete the orphaned server entry.
                    activeKeys != null && notificationKey !in activeKeys -> {
                        Log.d(TAG, "Fallback poll: notification $notificationKey gone from phone — deleting server entry $serverId")
                        settings.removeNotificationMapping(notificationKey)
                        try {
                            apiClient.deleteNotification(settings.endpoint, settings.userId, serverId)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    /**
     * Fires the action on the device and acknowledges dispatch to the server.
     * Called both from the FCM broadcast receiver and the fallback poll loop.
     *
     * The local mapping is removed first so that the resulting onNotificationRemoved callback
     * (fired when the source app dismisses the notification after the action) exits early and
     * does not issue a DELETE — the server entry is intentionally kept so the web UI retains
     * a history record of the action.
     */
    private suspend fun handleActionRequest(
        notificationKey: String,
        serverId: String,
        actionTaken: String,
        actionResponse: String?
    ) {
        settings.removeNotificationMapping(notificationKey)
        fireAction(notificationKey, actionTaken, actionResponse)
        try { apiClient.postActionDispatched(settings.endpoint, settings.userId, serverId) } catch (_: Exception) {}
    }

    /**
     * as reply text for actions that have RemoteInput slots (e.g. "Reply").
     *
     * The notification is left on the device after the action fires — the source app is
     * responsible for updating or dismissing it (e.g. Teams replaces it with a sent receipt).
     * The notification is only cancelled if no matching action can be found.
     *
     * Matching priority:
     * 1. Exact title match (case-insensitive)
     * 2. [actionTitle] parsed as a semantic action integer (e.g. "8" → THUMBS_UP)
     * 3. Keyword/emoji alias mapped to a semantic action (e.g. "like" or "👍" → THUMBS_UP)
     */
    private fun fireAction(notificationKey: String, actionTitle: String, actionResponse: String? = null) {
        val sbn = try { activeNotifications?.find { it.key == notificationKey } } catch (_: Exception) { null }
        if (sbn != null) {
            val actions = sbn.notification.actions
            val action = actions?.find { it.title?.toString().equals(actionTitle, ignoreCase = true) }
                ?: actionTitle.toIntOrNull()?.let { semanticInt ->
                    actions?.find { it.semanticAction == semanticInt }
                }
                ?: SEMANTIC_ACTION_ALIASES[actionTitle.trim().lowercase()]?.let { semanticInt ->
                    actions?.find { it.semanticAction == semanticInt }
                }

            if (action?.actionIntent != null) {
                try {
                    val remoteInputs = action.remoteInputs
                    if (!remoteInputs.isNullOrEmpty() && actionResponse != null) {
                        // Fill each RemoteInput slot with the response text and send via a fill-in Intent
                        val results = Bundle()
                        for (ri in remoteInputs) {
                            results.putCharSequence(ri.resultKey, actionResponse)
                        }
                        val fillIn = Intent().apply {
                            RemoteInput.addResultsToIntent(remoteInputs, this, results)
                        }
                        action.actionIntent.send(this, 0, fillIn)
                    } else {
                        action.actionIntent.send()
                    }
                    Log.d(TAG, "Fired action '${action.title}' for ${sbn.packageName}")
                    // Do not cancel the notification — the source app will update or dismiss
                    // it as appropriate (e.g. Teams updates the notification after a reply).
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fire action PendingIntent: ${e.message}")
                }
            }
        }
        // Fallback: no matching action found — dismiss the notification
        Log.d(TAG, "No matching action found for '$actionTitle' — dismissing notification")
        try { cancelNotification(notificationKey) } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!settings.isConfigured) return
        if (sbn.packageName == packageName) return
        scope.launch {
            val key = sbn.key
            mutexFor(key).withLock {
                val existingServerId = settings.getNotificationServerId(key)
                if (existingServerId != null) {
                    // Notification was updated — delete old server entry and re-post with fresh content
                    try {
                        apiClient.deleteNotification(settings.endpoint, settings.userId, existingServerId)
                    } catch (_: Exception) {}
                    settings.removeNotificationMapping(key)
                }
                postSbn(sbn)
            }
            keyMutexes.remove(key)
        }
    }

    private suspend fun postSbn(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString()

        // For the default SMS app, read the actual SMS body from the content provider to avoid
        // potentially redacted notification content (e.g. OTP codes hidden by Android).
        val smsBody: String? = if (sbn.packageName == Telephony.Sms.getDefaultSmsPackage(this)) {
            fetchSmsBody(sbn.postTime)
        } else null

        // MessagingStyle notifications (e.g. Messenger, WhatsApp) store the full
        // message history in android.messages as an array of Bundles.
        // Use the typed API on API 33+ to avoid silent deserialization failures.
        @Suppress("DEPRECATION")
        val messagesArray: Array<out Parcelable>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray("android.messages", Bundle::class.java)
        } else {
            extras.getParcelableArray("android.messages")
        }
        val structuredMessages: List<NotificationMessage>?
        val body: String

        if (smsBody != null) {
            structuredMessages = null
            body = smsBody
        } else if (!messagesArray.isNullOrEmpty()) {
            structuredMessages = messagesArray.mapNotNull { extractMessage(it) }
            if (structuredMessages.isNotEmpty()) {
                body = structuredMessages.joinToString("\n") { msg ->
                    if (msg.sender != null) "${msg.sender}: ${msg.text}" else msg.text
                }
            } else {
                // Parsing failed for all items — fall back to plain text fields
                body = bigText?.takeIf { it.isNotBlank() } ?: text
            }
        } else {
            structuredMessages = null
            body = bigText?.takeIf { it.isNotBlank() } ?: text
        }
        val appName = run {
            val fresh = getAppName(sbn.packageName)
            if (fresh != sbn.packageName) {
                // Successfully resolved a human-readable name — update cache and use it
                val cachedIcon = settings.getAppMeta(sbn.packageName)?.icon
                settings.storeAppMeta(sbn.packageName, fresh, cachedIcon)
                fresh
            } else {
                // Fell back to package name — prefer cached name if available
                settings.getAppMeta(sbn.packageName)?.name ?: fresh
            }
        }
        val iconBase64 = run {
            val fresh = getAppIconBase64(sbn.packageName)
            if (fresh != null) {
                // Successfully rendered icon — update cache and use it
                val cachedName = settings.getAppMeta(sbn.packageName)?.name ?: appName
                settings.storeAppMeta(sbn.packageName, cachedName, fresh)
                fresh
            } else {
                // Rendering failed — fall back to cached icon
                settings.getAppMeta(sbn.packageName)?.icon
            }
        }
        val actions = sbn.notification.actions
            ?.mapNotNull { action ->
                val title = action.title?.toString().orEmpty()
                if (title.isBlank()) null else Pair(action.semanticAction, title)
            }
            ?.takeIf { it.isNotEmpty() }
        val nm = getSystemService(NotificationManager::class.java)
        val channel = sbn.notification.channelId?.let { nm.getNotificationChannel(it) }
        val isSilent = channel?.importance?.let { it < NotificationManager.IMPORTANCE_DEFAULT } ?: false
        try {
            val serverId = apiClient.postNotification(
                endpoint = settings.endpoint,
                userId = settings.userId,
                title = title,
                body = body,
                timestampMs = sbn.postTime,
                sourcePackage = sbn.packageName,
                appName = appName,
                icon = iconBase64,
                actions = actions,
                messages = structuredMessages,
                isSilent = isSilent
            )
            if (serverId != null) {
                settings.storeNotificationMapping(sbn.key, serverId)
                Log.d(TAG, "Synced [${sbn.packageName}] \"$title\" → $serverId")
            } else {
                Log.w(TAG, "Server rejected notification from ${sbn.packageName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!settings.isConfigured) return

        val notificationKey = sbn.key
        val serverId = settings.getNotificationServerId(notificationKey) ?: return

        scope.launch {
            try {
                val success = apiClient.deleteNotification(
                    endpoint = settings.endpoint,
                    userId = settings.userId,
                    notificationId = serverId
                )
                settings.removeNotificationMapping(notificationKey)
                if (success) {
                    Log.d(TAG, "Deleted notification $serverId from server")
                } else {
                    Log.w(TAG, "Server returned error when deleting $serverId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete notification: ${e.message}")
            }
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    /**
     * Queries the SMS content provider for the inbox message that arrived around [timestampMs].
     * Returns the raw SMS body, or null if no matching message is found or permission is denied.
     * Used to retrieve unredacted content (e.g. OTP codes) that Android may hide in notifications.
     */
    private fun fetchSmsBody(timestampMs: Long): String? {
        return try {
            val projection = arrayOf(Telephony.Sms.BODY)
            val selection = "${Telephony.Sms.DATE} BETWEEN ? AND ? AND ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}"
            val selectionArgs = arrayOf(
                (timestampMs - 30_000).toString(),
                (timestampMs + 5_000).toString()
            )
            contentResolver.query(
                Telephony.Sms.CONTENT_URI, projection, selection, selectionArgs,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY))
                else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch SMS body from content provider: ${e.message}")
            null
        }
    }

    private suspend fun extractMessage(item: Any?): NotificationMessage? {
        val bundle = item as? android.os.Bundle ?: return null
        val msgText = bundle.getCharSequence("text")?.toString()?.takeIf { it.isNotBlank() }
            ?: return null
        val timestampMs = bundle.getLong("time", 0L)

        // Sender name: prefer sender_person.name, fall back to top-level "sender"
        val personBundle = bundle.getBundle("sender_person")
        val senderName = personBundle?.getCharSequence("name")?.toString()
            ?: bundle.getCharSequence("sender")?.toString()

        // Sender avatar from Person.icon
        val senderIcon = personBundle?.let { person ->
            try {
                @Suppress("DEPRECATION")
                val icon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    person.getParcelable("icon", android.graphics.drawable.Icon::class.java)
                } else {
                    person.getParcelable("icon")
                }
                icon?.let { getSenderIconBase64(it) }
            } catch (_: Exception) { null }
        }

        return NotificationMessage(
            sender = senderName,
            text = msgText,
            timestampMs = timestampMs,
            senderIcon = senderIcon
        )
    }

    private suspend fun getSenderIconBase64(icon: android.graphics.drawable.Icon): String? =
        withContext(Dispatchers.Main) {
            try {
                val drawable = icon.loadDrawable(this@NotificationSyncService) ?: return@withContext null
                drawableToBase64(drawable)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract sender icon: ${e.message}")
                null
            }
        }

    private suspend fun getAppIconBase64(packageName: String): String? = withContext(Dispatchers.Main) {
        try {
            drawableToBase64(packageManager.getApplicationIcon(packageName))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get icon for $packageName: ${e.message}")
            null
        }
    }

    /** Renders [drawable] into a [ICON_SIZE]×[ICON_SIZE] bitmap and returns a Base64-encoded PNG. */
    private fun drawableToBase64(drawable: Drawable): String {
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
            drawable.background?.apply { setBounds(0, 0, ICON_SIZE, ICON_SIZE); draw(canvas) }
            drawable.foreground?.apply { setBounds(0, 0, ICON_SIZE, ICON_SIZE); draw(canvas) }
        } else {
            drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
            drawable.draw(canvas)
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }
}

