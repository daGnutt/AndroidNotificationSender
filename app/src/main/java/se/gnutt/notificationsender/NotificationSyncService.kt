package se.gnutt.notificationsender

import android.graphics.Bitmap
import android.graphics.Canvas
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
import java.io.ByteArrayOutputStream

class NotificationSyncService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationSync"
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var settings: SettingsManager
    private lateinit var apiClient: ApiClient

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        apiClient = ApiClient()
        Log.i(TAG, "NotificationSyncService started")
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        Log.i(TAG, "NotificationSyncService stopped")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (!settings.isConfigured) return
        Log.i(TAG, "Listener connected — syncing active notifications")
        scope.launch {
            val active = try { activeNotifications } catch (e: Exception) { null } ?: return@launch
            val activeKeys = active.map { it.key }.toSet()

            // Delete server entries whose notification is no longer on the phone
            val localMappings = settings.getAllMappings()
            for ((notificationKey, serverId) in localMappings) {
                if (notificationKey !in activeKeys) {
                    Log.d(TAG, "Cleaning up orphaned server entry $serverId")
                    try {
                        apiClient.deleteNotification(settings.endpoint, settings.userId, serverId)
                    } catch (_: Exception) {}
                    settings.removeNotificationMapping(notificationKey)
                }
            }

            // Post active notifications not yet tracked
            for (sbn in active) {
                if (sbn.packageName == packageName) continue
                if (settings.getNotificationServerId(sbn.key) != null) continue
                postSbn(sbn)
            }
        }
        scope.launch { pollServerDismissals() }
    }

    private suspend fun pollServerDismissals() {
        while (scope.isActive) {
            delay(10_000)
            if (!settings.isConfigured) continue
            val serverIds = apiClient.getNotificationIds(settings.endpoint, settings.userId) ?: continue
            val localMappings = settings.getAllMappings() // notificationKey -> serverId
            for ((notificationKey, serverId) in localMappings) {
                if (serverId !in serverIds) {
                    Log.d(TAG, "Server dismissed $serverId — cancelling local notification")
                    // Remove mapping first so onNotificationRemoved won't re-delete from server
                    settings.removeNotificationMapping(notificationKey)
                    try {
                        cancelNotification(notificationKey)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to cancel notification: ${e.message}")
                    }
                }
            }
        }
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

    private fun getAppIconBase64(packageName: String): String? {
        return try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, 96, 96)
            drawable.draw(canvas)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get icon for $packageName: ${e.message}")
            null
        }
    }
}

