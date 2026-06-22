package se.gnutt.notificationsender

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
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
import java.util.Collections
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
    // Used only for posting/deleting the Wi-Fi offline status notification — bypasses the
    // wifiOnly network policy so the call can reach the server via mobile data when Wi-Fi drops.
    private val unrestrictedApiClient = ApiClient()
    private var mediaSessionMonitor: MediaSessionMonitor? = null

    // Monitors connectivity changes to maintain the Wi-Fi offline status notification on the
    // server when wifiOnly sync is enabled and the device is not on Wi-Fi/Ethernet.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!settings.wifiOnly) return
            if (isNetworkAllowed(this@NotificationSyncService, settings)) {
                scope.launch {
                    clearOfflineNotification()
                    if (settings.isConfigured) fullSync()
                }
            }
        }

        override fun onLost(network: Network) {
            if (!settings.wifiOnly) return
            if (!isNetworkAllowed(this@NotificationSyncService, settings)) {
                scope.launch { postOfflineNotification() }
            }
        }
    }

    // Serialises concurrent onNotificationPosted calls for the same notification key,
    // preventing race conditions that create duplicate server entries.
    private val keyMutexes = ConcurrentHashMap<String, Mutex>()
    private fun mutexFor(key: String) = keyMutexes.computeIfAbsent(key) { Mutex() }

    // Tracks keys that went through the "already gone" sub-second cleanup path, with the
    // cleanup timestamp in milliseconds. When onNotificationPosted fires multiple times in
    // rapid succession for the same short-lived notification (e.g. Android 15 OTP SMS posts
    // the notification 3× within 22 ms), only the first coroutine should post to the server;
    // subsequent ones must be suppressed or they create duplicate server entries. The TTL is
    // 10 s — long enough to cover any burst of rapid updates, short enough not to suppress a
    // legitimate re-show of the same notification key after user interaction.
    private val recentlyGoneKeys = ConcurrentHashMap<String, Long>()
    private fun markRecentlyGone(key: String) { recentlyGoneKeys[key] = System.currentTimeMillis() }
    private fun isRecentlyGone(key: String): Boolean {
        val ts = recentlyGoneKeys[key] ?: return false
        if (System.currentTimeMillis() - ts > 10_000L) { recentlyGoneKeys.remove(key); return false }
        return true
    }

    // In-memory queue for FCM dismiss/action commands that arrive before postSbn has stored the
    // server-ID → notification-key mapping. Keyed by serverId. Items are drained inside postSbn
    // immediately after storeNotificationMapping(), so the window is bounded by the POST round-trip.
    private data class FcmCommand(val type: String, val actionTaken: String?, val actionResponse: String?)
    private val pendingFcmQueue = ConcurrentHashMap<String, MutableList<FcmCommand>>()

    // Tracks server IDs for which an action has already been fired this session.
    // Prevents the fallback poll from re-firing an action whose /dispatched call
    // failed (server still shows actionDispatched=false on the next cycle), and
    // prevents an FCM delivery and a concurrent poll cycle from both firing the
    // same action. Uses ConcurrentHashMap.add() which is atomic: if add() returns
    // false the entry was already present and the caller must skip firing.
    private val firedActionIds: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Manual refresh requested")
            scope.launch { fullSync() }
        }
    }

    // Fired when the device is unlocked. Immediately checks for any pending actions
    // that were deferred while the keyguard was active, avoiding up to 5 minutes of
    // waiting for the scheduled poll cycle when FCM is active.
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Device unlocked — checking for deferred actions")
            scope.launch { checkPendingActions() }
        }
    }

    private val fcmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cmdId = intent.getStringExtra(FcmService.EXTRA_CMD_ID)
            when (intent.action) {
                FcmService.ACTION_FCM_DISMISS -> {
                    val serverId = intent.getStringExtra(FcmService.EXTRA_SERVER_ID) ?: return
                    // Mark the persisted command handled before processing so a concurrent drain
                    // on service restart doesn't also process it.
                    if (cmdId != null) settings.removePendingFcmCommand(cmdId)
                    val notificationKey = settings.getNotificationKeyByServerId(serverId)
                    if (notificationKey == null) {
                        // Mapping not stored yet (postSbn still in flight) — queue for when it lands.
                        Log.d(TAG, "FCM dismiss for $serverId arrived before mapping — queuing")
                        pendingFcmQueue.computeIfAbsent(serverId) { Collections.synchronizedList(mutableListOf()) }
                            .add(FcmCommand("dismiss", null, null))
                        return
                    }
                    Log.d(TAG, "FCM dismiss for server entry $serverId")
                    settings.removeNotificationMapping(notificationKey)
                    safeCancelNotification(notificationKey)
                }
                FcmService.ACTION_FCM_ACTION -> {
                    val serverId = intent.getStringExtra(FcmService.EXTRA_SERVER_ID) ?: return
                    val actionTaken = intent.getStringExtra(FcmService.EXTRA_ACTION_TAKEN) ?: return
                    if (cmdId != null) settings.removePendingFcmCommand(cmdId)
                    val notificationKey = settings.getNotificationKeyByServerId(serverId)
                    if (notificationKey == null) {
                        Log.d(TAG, "FCM action '$actionTaken' for $serverId arrived before mapping — queuing")
                        val actionResponse = intent.getStringExtra(FcmService.EXTRA_ACTION_RESPONSE)
                        pendingFcmQueue.computeIfAbsent(serverId) { Collections.synchronizedList(mutableListOf()) }
                            .add(FcmCommand("action", actionTaken, actionResponse))
                        return
                    }
                    val actionResponse = intent.getStringExtra(FcmService.EXTRA_ACTION_RESPONSE)
                    Log.d(TAG, "FCM action '$actionTaken' for server entry $serverId")
                    scope.launch { handleActionRequest(notificationKey, serverId, actionTaken, actionResponse) }
                }
                FcmService.ACTION_FCM_RESYNC -> {
                    Log.i(TAG, "FCM resync received — triggering full sync")
                    scope.launch { fullSync() }
                }
                FcmService.ACTION_FCM_MEDIA_CONTROL -> {
                    val sessionId = intent.getStringExtra(FcmService.EXTRA_SESSION_ID) ?: return
                    val action = intent.getStringExtra(FcmService.EXTRA_MEDIA_ACTION) ?: return
                    val positionMs = intent.getLongExtra(FcmService.EXTRA_POSITION_MS, -1L)
                    Log.d(TAG, "FCM mediaControl: $action on session $sessionId")
                    handleMediaControlRequest(sessionId, action, positionMs.takeIf { it >= 0 })
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        apiClient = ApiClient { isNetworkAllowed(this, settings) }
        ContextCompat.registerReceiver(this, refreshReceiver, IntentFilter(ACTION_REFRESH), ContextCompat.RECEIVER_NOT_EXPORTED)
        val fcmFilter = IntentFilter().apply {
            addAction(FcmService.ACTION_FCM_DISMISS)
            addAction(FcmService.ACTION_FCM_ACTION)
            addAction(FcmService.ACTION_FCM_RESYNC)
            addAction(FcmService.ACTION_FCM_MEDIA_CONTROL)
        }
        ContextCompat.registerReceiver(this, fcmReceiver, fcmFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // ACTION_USER_PRESENT is a protected broadcast — exported flag not required,
        // but we still restrict the receiver to this package for safety.
        registerReceiver(userPresentReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
        Log.i(TAG, "NotificationSyncService started")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(refreshReceiver)
        unregisterReceiver(fcmReceiver)
        unregisterReceiver(userPresentReceiver)
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        connectivityManager?.unregisterNetworkCallback(networkCallback)
        mediaSessionMonitor?.stop()
        job.cancel()
        Log.i(TAG, "NotificationSyncService stopped")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (!settings.isConfigured) return
        Log.i(TAG, "Listener connected — syncing active notifications")
        mediaSessionMonitor = MediaSessionMonitor(
            context = this,
            notificationListenerComponent = android.content.ComponentName(this, NotificationSyncService::class.java),
            scope = scope,
            settings = settings,
            apiClient = apiClient
        ).also { it.start() }
        scope.launch {
            // If wifiOnly is on and we're currently off-network, post the offline card so the
            // server knows syncing is paused. Do this before fullSync so the server reflects
            // the correct state immediately on listener reconnect.
            if (settings.wifiOnly && !isNetworkAllowed(this@NotificationSyncService, settings)) {
                postOfflineNotification()
            }
            fullSync()
            // Drain any FCM commands that arrived while the service was not alive.
            // Running after fullSync ensures all server-ID mappings are freshly populated.
            drainStoredFcmCommands()
        }
        scope.launch { pollServerDismissals() }
        registerFcmToken()
    }

    private suspend fun drainStoredFcmCommands() {
        val commands = settings.drainPendingFcmCommands()
        if (commands.isEmpty()) return
        Log.i(TAG, "Draining ${commands.size} stored FCM command(s) from before service was alive")
        for (cmd in commands) {
            when (cmd.type) {
                "dismiss" -> {
                    val notificationKey = settings.getNotificationKeyByServerId(cmd.serverId) ?: continue
                    Log.d(TAG, "Stored FCM dismiss for server entry ${cmd.serverId}")
                    settings.removeNotificationMapping(notificationKey)
                    safeCancelNotification(notificationKey)
                }
                "action" -> {
                    val actionTaken = cmd.actionTaken ?: continue
                    val notificationKey = settings.getNotificationKeyByServerId(cmd.serverId) ?: continue
                    Log.d(TAG, "Stored FCM action '$actionTaken' for server entry ${cmd.serverId}")
                    handleActionRequest(notificationKey, cmd.serverId, actionTaken, cmd.actionResponse)
                }
            }
        }
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

    /**
     * Posts a silent "Sync paused" status notification to the server so the web UI knows
     * that Wi-Fi-only sync is currently inactive. Uses [unrestrictedApiClient] so the call
     * can succeed over mobile data even though [settings.wifiOnly] is enabled.
     * Idempotent: does nothing if the offline card is already stored.
     */
    private suspend fun postOfflineNotification() {
        if (!settings.isConfigured) return
        if (settings.wifiOfflineServerId != null) return
        Log.i(TAG, "Wi-Fi lost — posting offline status notification to server")
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) { "Notification Sender" }
        val id = unrestrictedApiClient.postNotification(
            endpoint = settings.endpoint,
            userId = settings.userId,
            title = "Sync paused — not on Wi-Fi",
            body = "New notifications won't sync until reconnected to Wi-Fi.",
            timestampMs = System.currentTimeMillis(),
            sourcePackage = packageName,
            appName = appName,
            isSilent = true
        )
        if (id != null) {
            settings.wifiOfflineServerId = id
            Log.i(TAG, "Offline status notification posted (id=$id)")
        } else {
            Log.w(TAG, "Failed to post offline status notification (no network or server error)")
        }
    }

    /**
     * Removes the offline status notification from the server when Wi-Fi is restored.
     * Idempotent: does nothing if no offline card is stored.
     */
    private suspend fun clearOfflineNotification() {
        val id = settings.wifiOfflineServerId ?: return
        // Clear the stored ID first so a concurrent call or service restart doesn't retry.
        settings.wifiOfflineServerId = null
        Log.i(TAG, "Wi-Fi available — removing offline status notification (id=$id)")
        apiClient.deleteNotification(settings.endpoint, settings.userId, id)
    }

    private suspend fun fullSync() {
        val active = try { activeNotifications } catch (e: Exception) { null } ?: return

        // Delete all locally-known server entries. Entries that return ActionPending (409) are
        // intentionally kept by the server as history records; skip their mapping removal so the
        // re-post loop below also skips them (no point re-posting a frozen history entry).
        val localMappings = settings.getAllMappings()
        val actionPendingKeys = mutableSetOf<String>()
        for ((notificationKey, serverId) in localMappings) {
            val result = apiClient.deleteNotification(settings.endpoint, settings.userId, serverId)
            if (result == DeleteResult.ActionPending) {
                Log.i(TAG, "fullSync: server entry $serverId has pending action — keeping as history, skipping re-post")
                actionPendingKeys += notificationKey
            } else {
                settings.removeNotificationMapping(notificationKey)
            }
        }

        // Purge any remaining server entries — this catches both entries whose delete
        // failed above (network error) and true orphans from a previous crash where
        // storeNotificationMapping never ran.
        val serverNotifications = apiClient.getNotifications(settings.endpoint, settings.userId)
        if (serverNotifications != null) {
            for (serverNotif in serverNotifications) {
                val result = apiClient.deleteNotification(settings.endpoint, settings.userId, serverNotif.id)
                when (result) {
                    DeleteResult.ActionPending ->
                        Log.i(TAG, "fullSync: skipping 409-protected orphan ${serverNotif.id} — kept as history")
                    else ->
                        Log.d(TAG, "Purged server entry ${serverNotif.id}")
                }
            }
        }

        // Re-snapshot active notifications immediately before posting to avoid a race
        // where a notification in the earlier snapshot has since been dismissed: in that
        // case onNotificationRemoved would see no mapping and exit early, leaving the
        // freshly-posted server entry orphaned.
        val currentActiveKeys = safeActiveKeys()

        // Post all currently active notifications (skip those whose server entry is frozen as history)
        for (sbn in active) {
            if (sbn.packageName == packageName) continue
            if (currentActiveKeys != null && sbn.key !in currentActiveKeys) continue
            if (sbn.key in actionPendingKeys) continue
            // Use the key mutex so we don't race with a concurrent onNotificationPosted.
            // If onNotificationPosted already posted this notification while we were purging
            // server entries, the mapping will exist and we skip to avoid a duplicate.
            mutexFor(sbn.key).withLock {
                if (settings.getNotificationServerId(sbn.key) == null) {
                    postSbn(sbn)
                }
            }
        }
        Log.i(TAG, "Full sync complete — posted ${active.count { it.packageName != packageName }} notifications")
    }

    /**
     * Scans for server-side actions that have not yet been dispatched and fires them.
     * Called immediately when the device is unlocked (via [userPresentReceiver]) to avoid
     * waiting up to 5 minutes for the scheduled poll cycle when FCM is active.
     */
    private suspend fun checkPendingActions() {
        if (!settings.isConfigured) return
        val serverNotifications = apiClient.getNotifications(settings.endpoint, settings.userId) ?: return
        val localMappings = settings.getAllMappings()
        for ((notificationKey, serverId) in localMappings) {
            val serverNotif = serverNotifications.find { it.id == serverId } ?: continue
            if (serverNotif.actionTaken != null && !serverNotif.actionDispatched) {
                Log.d(TAG, "checkPendingActions: firing deferred action '${serverNotif.actionTaken}' for $serverId")
                handleActionRequest(notificationKey, serverId, serverNotif.actionTaken, serverNotif.actionResponse)
            }
        }
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
            val activeKeys = safeActiveKeys()

            // Detect server restart: the server holds notifications in memory only, so a
            // restart wipes all entries.  If the server returns an empty list but we still
            // have local mappings, assume a restart rather than mass-dismissal and resync
            // (re-post everything) instead of cancelling the phone notifications.
            if (serverNotifications.isEmpty() && localMappings.isNotEmpty()) {
                Log.i(TAG, "Server returned empty list with ${localMappings.size} local mappings — server may have restarted, resyncing")
                fullSync()
                // Re-register FCM token in case a server restart also cleared the device_tokens
                // table. Called here (not inside fullSync) to avoid a resync→fullSync loop.
                registerFcmToken()
                continue
            }

            for ((notificationKey, serverId) in localMappings) {
                val serverNotif = serverNotifications.find { it.id == serverId }

                when {
                    serverNotif == null -> {
                        Log.d(TAG, "Fallback poll: server dismissed $serverId — cancelling local notification")
                        settings.removeNotificationMapping(notificationKey)
                        safeCancelNotification(notificationKey)
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
                        val result = apiClient.deleteNotification(settings.endpoint, settings.userId, serverId)
                        if (result == DeleteResult.ActionPending) {
                            Log.i(TAG, "Fallback poll: server entry $serverId has pending action — kept as history (notification already gone from phone)")
                        }
                    }
                }
            }
        }
    }

    /**
     * Dispatches a media transport control command to the appropriate [MediaController].
     * Called from the FCM broadcast receiver when a "mediaControl" message arrives.
     */
    private fun handleMediaControlRequest(sessionId: String, action: String, positionMs: Long?) {
        val controller = mediaSessionMonitor?.getController(sessionId)
        if (controller == null) {
            Log.w(TAG, "No active MediaController for session $sessionId — ignoring $action")
            return
        }
        val controls = controller.transportControls
        when (action) {
            "play"     -> controls.play()
            "pause"    -> controls.pause()
            "next"     -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
            "seekTo"   -> positionMs?.let { controls.seekTo(it) }
                ?: Log.w(TAG, "seekTo missing positionMs for session $sessionId")
            "stop"     -> controls.stop()
            else       -> Log.w(TAG, "Unknown media control action: $action")
        }
        Log.d(TAG, "Dispatched media control '$action' to session $sessionId")
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
        // Defer the action if the device is locked. Activity-type PendingIntents cannot be
        // launched from a background service while the keyguard is active — they either throw
        // a SecurityException or silently no-op. By returning here without adding to
        // firedActionIds, the fallback poll loop (or the userPresentReceiver → checkPendingActions)
        // will retry automatically once the device is unlocked.
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) {
            Log.d(TAG, "Device is locked — deferring action '$actionTaken' for $serverId until unlock")
            return
        }

        // Atomically claim this action. If add() returns false it was already claimed by a
        // concurrent FCM delivery or a previous poll cycle whose /dispatched call failed —
        // skip to avoid firing the action a second time.
        if (!firedActionIds.add(serverId)) {
            Log.d(TAG, "Action '$actionTaken' for $serverId already fired this session — skipping duplicate")
            return
        }
        settings.removeNotificationMapping(notificationKey)
        val dispatched = fireAction(notificationKey, actionTaken, actionResponse)
        if (dispatched) {
            try { apiClient.postActionDispatched(settings.endpoint, settings.userId, serverId) } catch (_: Exception) {}
        } else {
            // PendingIntent.send() threw — remove from firedActionIds so the poll loop can
            // retry on the next cycle rather than silently dropping the action for this session.
            firedActionIds.remove(serverId)
        }
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
    // Returns true when the action was handled (PendingIntent fired, or notification dismissed as
    // fallback). Returns false only when PendingIntent.send() threw — in that case the caller
    // must not acknowledge dispatch so the server can retry.
    private fun fireAction(notificationKey: String, actionTitle: String, actionResponse: String? = null): Boolean {
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
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fire action PendingIntent: ${e.message}")
                    // Do not fall through to dismiss — the notification is likely still active.
                    // Returning false signals the caller to leave the server action unacknowledged
                    // so that it can be retried on the next poll cycle or service restart.
                    return false
                }
            }
        }
        // Fallback: notification gone or no matching action — dismiss and acknowledge.
        Log.d(TAG, "No matching action found for '$actionTitle' — dismissing notification")
        try { cancelNotification(notificationKey) } catch (_: Exception) {}
        return true
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!settings.isConfigured) return
        if (sbn.packageName == packageName) return
        scope.launch {
            val key = sbn.key
            mutexFor(key).withLock {
                // Suppress rapid-fire duplicate posts for a key that just went through the
                // "already gone" cleanup path (e.g. Android 15 OTP SMS fires onNotificationPosted
                // 3× within 22 ms for the same notification; only the first should post).
                if (isRecentlyGone(key)) {
                    Log.d(TAG, "Notification $key suppressed — recently cleaned up as short-lived")
                    return@withLock
                }

                val existingServerId = settings.getNotificationServerId(key)
                if (existingServerId != null) {
                    // Notification was updated — delete old server entry and re-post with fresh content.
                    // If the server refuses deletion because an action is pending (409), the existing
                    // entry is frozen as a history record and cannot be replaced; skip the re-post.
                    val deleteResult = apiClient.deleteNotification(settings.endpoint, settings.userId, existingServerId)
                    if (deleteResult == DeleteResult.ActionPending) {
                        Log.i(TAG, "Notification $key updated but server entry $existingServerId has pending action — skipping re-post, keeping history entry")
                        return@withLock
                    }
                    settings.removeNotificationMapping(key)
                }
                postSbn(sbn)

                // If we deleted an old server entry but the new POST failed, the notification
                // has vanished from the backend. Trigger a full sync so the re-post happens
                // from the current active notification snapshot.
                if (existingServerId != null && settings.getNotificationServerId(key) == null) {
                    Log.w(TAG, "Notification $key update lost after deleting $existingServerId — triggering resync")
                    scope.launch { fullSync() }
                }

                // If the notification was already dismissed while we were posting (sub-second
                // notifications), onNotificationRemoved would have found no mapping and exited
                // early. Clean up the server entry we just created.
                val storedServerId = settings.getNotificationServerId(key)
                if (storedServerId != null) {
                    val stillActive = try { activeNotifications?.any { it.key == key } } catch (_: Exception) { true }
                    if (stillActive == false) {
                        Log.d(TAG, "Notification $key already gone — deleting server entry $storedServerId")
                        settings.removeNotificationMapping(key)
                        markRecentlyGone(key)
                        val cleanupResult = apiClient.deleteNotification(settings.endpoint, settings.userId, storedServerId)
                        if (cleanupResult == DeleteResult.ActionPending) {
                            Log.i(TAG, "Sub-second cleanup: server entry $storedServerId has pending action — kept as history")
                        }
                    }
                }
            }
            // Note: keyMutexes entries are intentionally left in place. Removing a mutex
            // outside its withLock block is a race: a waiting coroutine holds a reference to
            // the old mutex while a new coroutine creates a fresh one, defeating mutual
            // exclusion and allowing concurrent postSbn calls for the same key.
        }
    }

    // Holds the SMS body and sender address fetched from the Telephony content provider,
    // used by enrichSmsBody to replace Android 15's redacted notification content.
    private data class SmsData(val body: String, val sender: String)

    // smsData is set by enrichSmsBody when re-posting with the real SMS content (fallback path).
    // For the initial post from the default SMS app, fetchSms() is tried immediately — Android 15
    // redacts OTP/sensitive SMS notifications before the NLS sees them, but the SMS is already in
    // the Telephony content provider by the time onNotificationPosted fires. The background
    // enrichSmsBody is kept only as a fallback for devices where the content provider write lags.
    private suspend fun postSbn(sbn: StatusBarNotification, smsData: SmsData? = null) {
        val extras = sbn.notification.extras

        // Group summary notifications (FLAG_GROUP_SUMMARY) are technical wrappers used by Android
        // to bundle individual conversation notifications. They don't represent a discrete message
        // and should not be forwarded individually.
        if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) {
            Log.d(TAG, "Skipping group summary notification from ${sbn.packageName}")
            return
        }

        // Immediately try SMS lookup for the default SMS app on every initial post.
        val defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(this)
        val effectiveSmsData: SmsData? = smsData
            ?: if (sbn.packageName == defaultSmsPackage) fetchSms(sbn.postTime) else null

        // For the default SMS app, if the SMS database lookup failed and the notification title
        // is blank, the notification is redacted by Android 15 (OTP/sensitive content from an
        // older conversation re-posted when a new SMS arrived). Skip it — posting the redacted
        // placeholder text is worse than not posting at all. enrichSmsBody will retry if needed.
        val extrasTitle = extras.getCharSequence("android.title")?.toString().orEmpty()
        if (sbn.packageName == defaultSmsPackage && effectiveSmsData == null && extrasTitle.isBlank()) {
            Log.d(TAG, "Skipping redacted SMS notification from ${sbn.packageName} (fetchSms failed, title blank)")
            return
        }

        val title = effectiveSmsData?.sender ?: extrasTitle
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString()

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

        if (effectiveSmsData != null) {
            // Use the content fetched directly from the Telephony provider.
            structuredMessages = null
            body = effectiveSmsData.body
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
                val cachedIcon = settings.getAppMeta(sbn.packageName)?.icon
                settings.storeAppMeta(sbn.packageName, fresh, cachedIcon)
                fresh
            } else {
                settings.getAppMeta(sbn.packageName)?.name ?: fresh
            }
        }

        // Serve icon from cache immediately; only render (expensive) when the cache is empty.
        // Icons are stable per-app, so a stale cached icon is almost always correct.
        val iconBase64 = run {
            val cached = settings.getAppMeta(sbn.packageName)?.icon
            if (cached != null) {
                cached
            } else {
                val fresh = getAppIconBase64(sbn.packageName)
                if (fresh != null) {
                    val cachedName = settings.getAppMeta(sbn.packageName)?.name ?: appName
                    settings.storeAppMeta(sbn.packageName, cachedName, fresh)
                }
                fresh
            }
        }

        val actions = sbn.notification.actions
            ?.mapNotNull { action ->
                val actionTitle = action.title?.toString().orEmpty()
                if (actionTitle.isBlank()) null else Pair(action.semanticAction, actionTitle)
            }
            ?.takeIf { it.isNotEmpty() }
        // Use the NotificationListenerService ranking API to get the source app's channel.
        // NotificationManager.getNotificationChannel() only resolves channels for the calling
        // package, so it always returns null for third-party notifications.
        val isSilent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val ranking = Ranking()
            currentRanking.getRanking(sbn.key, ranking)
            ranking.channel?.importance?.let { it < NotificationManager.IMPORTANCE_DEFAULT } ?: false
        } else {
            false
        }
        try {
            val serverId = apiClient.postNotification(
                endpoint = settings.endpoint,
                userId = settings.userId,
                title = title,
                body = body,
                timestampMs = sbn.notification.`when`.takeIf { it > 0L } ?: sbn.postTime,
                sourcePackage = sbn.packageName,
                appName = appName,
                icon = iconBase64,
                actions = actions,
                messages = structuredMessages,
                isSilent = isSilent,
                androidKey = sbn.key
            )
            if (serverId != null) {
                settings.storeNotificationMapping(sbn.key, serverId)
                Log.d(TAG, "Synced [${sbn.packageName}] key=${sbn.key} \"$title\" → $serverId")
                // Drain FCM commands that arrived before this mapping was ready (race fix #12).
                // pendingFcmQueue.remove is atomic; the detached list is only accessible here.
                pendingFcmQueue.remove(serverId)?.let { queued ->
                    if (queued.isNotEmpty()) Log.d(TAG, "Draining ${queued.size} queued FCM command(s) for $serverId")
                    for (cmd in queued) {
                        when (cmd.type) {
                            "dismiss" -> {
                                settings.removeNotificationMapping(sbn.key)
                                apiClient.deleteNotification(settings.endpoint, settings.userId, serverId)
                                safeCancelNotification(sbn.key)
                            }
                            "action" -> {
                                val actionTaken = cmd.actionTaken ?: continue
                                val notificationKey = sbn.key
                                scope.launch { handleActionRequest(notificationKey, serverId, actionTaken, cmd.actionResponse) }
                            }
                        }
                    }
                }
                // Launch background enrichment only if the immediate fetch failed — fallback for
                // devices where the SMS content provider write lags behind the notification post.
                // Note: if the notification is dismissed before this coroutine runs (common for
                // Android 15 OTP auto-dismiss), the guard in enrichSmsBody will bail early.
                if (effectiveSmsData == null && sbn.packageName == defaultSmsPackage) {
                    scope.launch { enrichSmsBody(sbn, serverId) }
                }
            } else {
                Log.w(TAG, "Server rejected notification from ${sbn.packageName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification: ${e.message}")
        }
    }

    private suspend fun enrichSmsBody(sbn: StatusBarNotification, originalServerId: String) {
        var smsData: SmsData? = null
        for (attempt in 1..3) {
            delay(1_000L)
            smsData = fetchSms(sbn.postTime)
            if (smsData != null) break
        }
        if (smsData == null) return
        // Update the server entry with the real SMS body via delete-and-repost under the key mutex.
        mutexFor(sbn.key).withLock {
            val currentServerId = settings.getNotificationServerId(sbn.key)
            if (currentServerId != originalServerId) return@withLock  // notification changed or dismissed
            val deleteResult = apiClient.deleteNotification(settings.endpoint, settings.userId, currentServerId)
            if (deleteResult == DeleteResult.ActionPending) return@withLock
            settings.removeNotificationMapping(sbn.key)
            postSbn(sbn, smsData = smsData)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!settings.isConfigured) return

        val notificationKey = sbn.key
        val serverId = settings.getNotificationServerId(notificationKey) ?: return
        // Remove the mapping before the async network call so that a concurrent
        // onNotificationPosted for the same key (notification re-shown) can store
        // a fresh mapping without it being wiped when this coroutine completes.
        settings.removeNotificationMapping(notificationKey)

        scope.launch {
            val result = apiClient.deleteNotification(
                endpoint = settings.endpoint,
                userId = settings.userId,
                notificationId = serverId
            )
            when (result) {
                DeleteResult.Success, DeleteResult.NotFound ->
                    Log.d(TAG, "Deleted notification $serverId from server")
                DeleteResult.ActionPending ->
                    Log.i(TAG, "Server entry $serverId kept as history — action was pending when user dismissed")
                is DeleteResult.Failure ->
                    Log.w(TAG, "Server returned error ${result.code} when deleting $serverId")
            }
        }
    }

    private fun safeCancelNotification(key: String) {
        try { cancelNotification(key) } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel notification: ${e.message}")
        }
    }

    private fun safeActiveKeys(): Set<String>? =
        try { activeNotifications?.map { it.key }?.toSet() } catch (_: Exception) { null }

    private fun getAppName(packageName: String): String = getAppName(packageName, packageManager)

    /**
     * Queries the SMS content provider for the inbox message that arrived around [timestampMs].
     * Returns an [SmsData] with the raw body and sender address, or null if:
     * - [READ_SMS][android.Manifest.permission.READ_SMS] has not been granted at runtime, or
     * - no matching message is found in the content provider.
     *
     * Used to retrieve unredacted content (e.g. OTP codes) and the real sender that Android 15
     * may hide in notifications via its sensitive-notification redaction mechanism.
     * A [SecurityException] catch is kept as a safety net in case the permission is revoked between
     * the check and the query.
     */
    private fun fetchSms(timestampMs: Long): SmsData? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val projection = arrayOf(Telephony.Sms.BODY, Telephony.Sms.ADDRESS)
            val selection = "${Telephony.Sms.DATE} BETWEEN ? AND ? AND ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}"
            val selectionArgs = arrayOf(
                (timestampMs - 30_000).toString(),
                (timestampMs + 5_000).toString()
            )
            contentResolver.query(
                Telephony.Sms.CONTENT_URI, projection, selection, selectionArgs,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: return null
                    val sender = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: ""
                    SmsData(body, sender)
                } else null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_SMS permission revoked between check and query: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch SMS from content provider: ${e.message}")
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
                drawableToBase64(drawable, ICON_SIZE)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract sender icon: ${e.message}")
                null
            }
        }

    private suspend fun getAppIconBase64(packageName: String): String? = withContext(Dispatchers.Main) {
        try {
            drawableToBase64(packageManager.getApplicationIcon(packageName), ICON_SIZE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get icon for $packageName: ${e.message}")
            null
        }
    }
}
