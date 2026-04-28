package se.gnutt.notificationsender

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FcmService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FcmService"

        /** Broadcast to dismiss a locally-displayed notification by server ID. */
        const val ACTION_FCM_DISMISS = "se.gnutt.notificationsender.FCM_DISMISS"

        /** Broadcast to fire a notification action by server ID. */
        const val ACTION_FCM_ACTION = "se.gnutt.notificationsender.FCM_ACTION"

        /** Broadcast to trigger a full resync of active notifications to the server. */
        const val ACTION_FCM_RESYNC = "se.gnutt.notificationsender.FCM_RESYNC"

        const val EXTRA_SERVER_ID = "serverId"
        const val EXTRA_ACTION_TAKEN = "actionTaken"
        const val EXTRA_ACTION_RESPONSE = "actionResponse"
        const val EXTRA_CMD_ID = "cmdId"
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    /**
     * Called when FCM issues a new registration token (first run or token rotation).
     * Re-registers the token with the backend so push delivery stays valid.
     */
    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token refreshed — re-registering with server")
        val settings = SettingsManager(this)
        if (!settings.isConfigured) {
            settings.fcmToken = token  // save for later when config is completed
            return
        }
        scope.launch {
            val error = ApiClient().registerFcmToken(settings.endpoint, settings.userId, token)
            if (error == null) {
                settings.fcmToken = token
                Log.i(TAG, "FCM token registered with server")
            } else {
                Log.e(TAG, "Failed to register FCM token: $error")
            }
        }
    }

    /**
     * Handles incoming FCM data messages from the backend.
     *
     * Expected payloads:
     *   Dismiss:  { "type": "dismiss", "notificationId": "<serverId>" }
     *   Action:   { "type": "action",  "notificationId": "<serverId>",
     *               "actionTaken": "<title>", "actionResponse": "<text|null>" }
     *
     * Messages are forwarded as local broadcasts to NotificationSyncService, which
     * holds the NotificationListenerService binding needed to cancel/fire notifications.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        Log.d(TAG, "FCM message received: type=${data["type"]}")
        val settings = SettingsManager(this)
        val cmdId = java.util.UUID.randomUUID().toString()

        when (data["type"]) {
            "dismiss" -> {
                val serverId = data["notificationId"] ?: run {
                    Log.w(TAG, "dismiss message missing notificationId")
                    return
                }
                // Persist before broadcasting so the command survives if the service is not alive.
                settings.storePendingFcmCommand(SettingsManager.PendingFcmCommand(cmdId, "dismiss", serverId, null, null))
                sendLocalBroadcast(ACTION_FCM_DISMISS) {
                    putExtra(EXTRA_SERVER_ID, serverId)
                    putExtra(EXTRA_CMD_ID, cmdId)
                }
            }

            "action" -> {
                val serverId = data["notificationId"] ?: run {
                    Log.w(TAG, "action message missing notificationId")
                    return
                }
                val actionTaken = data["actionTaken"] ?: run {
                    Log.w(TAG, "action message missing actionTaken")
                    return
                }
                val actionResponse = data["actionResponse"]
                settings.storePendingFcmCommand(SettingsManager.PendingFcmCommand(cmdId, "action", serverId, actionTaken, actionResponse))
                sendLocalBroadcast(ACTION_FCM_ACTION) {
                    putExtra(EXTRA_SERVER_ID, serverId)
                    putExtra(EXTRA_ACTION_TAKEN, actionTaken)
                    actionResponse?.let { putExtra(EXTRA_ACTION_RESPONSE, it) }
                    putExtra(EXTRA_CMD_ID, cmdId)
                }
            }

            "resync" -> {
                // No need to persist resync — onListenerConnected already performs a full sync.
                Log.i(TAG, "FCM resync requested — triggering full sync")
                sendLocalBroadcast(ACTION_FCM_RESYNC)
            }

            else -> Log.w(TAG, "Unknown FCM message type: ${data["type"]}")
        }
    }

    private fun sendLocalBroadcast(action: String, setup: Intent.() -> Unit = {}) {
        sendBroadcast(Intent(action).apply { setPackage(packageName); setup() })
    }
}
