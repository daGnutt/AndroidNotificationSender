package se.gnutt.notificationsender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class NotificationSyncService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationSync"
        const val ACTION_REFRESH = "se.gnutt.notificationsender.REFRESH_NOTIFICATIONS"
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var settings: SettingsManager
    private lateinit var apiClient: ApiClient

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Manual refresh requested")
            scope.launch { fullSync() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        apiClient = ApiClient()
        registerReceiver(refreshReceiver, IntentFilter(ACTION_REFRESH), RECEIVER_NOT_EXPORTED)
        Log.i(TAG, "NotificationSyncService started")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(refreshReceiver)
        job.cancel()
        Log.i(TAG, "NotificationSyncService stopped")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (!settings.isConfigured) return
        Log.i(TAG, "Listener connected — syncing active notifications")
        scope.launch { fullSync() }
        scope.launch { pollServerDismissals() }
    }

    private suspend fun fullSync() {
        val active = try { activeNotifications } catch (e: Exception) { null } ?: return
        val activeKeys = active.map { it.key }.toSet()

        // Delete all existing server mappings and re-post everything fresh
        val localMappings = settings.getAllMappings()
        for ((notificationKey, serverId) in localMappings) {
            try {
                apiClient.deleteNotification(settings.endpoint, settings.userId, serverId)
            } catch (_: Exception) {}
            settings.removeNotificationMapping(notificationKey)
        }

        // Post all currently active notifications
        for (sbn in active) {
            if (sbn.packageName == packageName) continue
            postSbn(sbn)
        }
        Log.i(TAG, "Full sync complete — posted ${active.count { it.packageName != packageName }} notifications")
    }

    private suspend fun pollServerDismissals() {
        while (scope.isActive) {
            delay(10_000)
            if (!settings.isConfigured) continue

            val serverNotifications = apiClient.getNotifications(settings.endpoint, settings.userId) ?: continue
            val serverIds = serverNotifications.map { it.id }.toSet()
            val localMappings = settings.getAllMappings() // notificationKey -> serverId

            for ((notificationKey, serverId) in localMappings) {
                val serverNotif = serverNotifications.find { it.id == serverId }

                when {
                    // Notification no longer exists on server — dismiss locally
                    serverNotif == null -> {
                        Log.d(TAG, "Server dismissed $serverId — cancelling local notification")
                        settings.removeNotificationMapping(notificationKey)
                        try { cancelNotification(notificationKey) } catch (e: Exception) {
                            Log.e(TAG, "Failed to cancel notification: ${e.message}")
                        }
                    }

                    // Action was taken on server — fire the action locally then clean up
                    serverNotif.actionTaken != null -> {
                        Log.d(TAG, "Action '${serverNotif.actionTaken}' requested for $serverId")
                        fireAction(notificationKey, serverNotif.actionTaken)
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
     * Fires the notification action whose title matches [actionTitle].
     * Falls back to cancelling the notification if no matching action is found.
     */
    private fun fireAction(notificationKey: String, actionTitle: String) {
        val sbn = try { activeNotifications?.find { it.key == notificationKey } } catch (_: Exception) { null }
        if (sbn != null) {
            val action = sbn.notification.actions?.find {
                it.title?.toString().equals(actionTitle, ignoreCase = true)
            }
            if (action?.actionIntent != null) {
                try {
                    action.actionIntent.send()
                    Log.d(TAG, "Fired action '${action.title}' for ${sbn.packageName}")
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fire action PendingIntent: ${e.message}")
                }
            }
        }
        // Fallback: just dismiss the notification
        Log.d(TAG, "No matching action found for '$actionTitle' — dismissing notification")
        try { cancelNotification(notificationKey) } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!settings.isConfigured) return
        if (sbn.packageName == packageName) return
        scope.launch {
            val existingServerId = settings.getNotificationServerId(sbn.key)
            if (existingServerId != null) {
                // Notification was updated — delete old server entry and re-post with fresh content
                try {
                    apiClient.deleteNotification(settings.endpoint, settings.userId, existingServerId)
                } catch (_: Exception) {}
                settings.removeNotificationMapping(sbn.key)
            }
            postSbn(sbn)
        }
    }

    private suspend fun postSbn(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString()
        val body = bigText?.takeIf { it.isNotBlank() } ?: text
        val appName = getAppName(sbn.packageName)
        val iconBase64 = getAppIconBase64(sbn.packageName)
        val actions = sbn.notification.actions
            ?.mapNotNull { action ->
                val title = action.title?.toString().orEmpty()
                if (title.isBlank()) null else Pair(action.semanticAction, title)
            }
            ?.takeIf { it.isNotEmpty() }
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
                actions = actions
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

    override fun onNotificationRemoved(sbn: StatusBarNotification) {        if (!settings.isConfigured) return

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

    private suspend fun getAppIconBase64(packageName: String): String? = withContext(Dispatchers.Main) {
        try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val size = 96
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                drawable is android.graphics.drawable.AdaptiveIconDrawable) {
                // Draw background and foreground layers separately
                drawable.background?.apply { setBounds(0, 0, size, size); draw(canvas) }
                drawable.foreground?.apply { setBounds(0, 0, size, size); draw(canvas) }
            } else {
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
            }
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get icon for $packageName: ${e.message}")
            null
        }
    }
}

