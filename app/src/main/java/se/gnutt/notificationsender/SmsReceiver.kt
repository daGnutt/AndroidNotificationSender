package se.gnutt.notificationsender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        const val SMS_SOURCE_PACKAGE = "android.telephony"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val settings = SettingsManager(context)
        if (!settings.isConfigured) return

        // Multi-part messages arrive as separate SmsMessage objects with the same sender.
        // Group by originating address and concatenate their bodies in order.
        val bySender = messages.groupBy { it.originatingAddress ?: "Unknown" }

        val apiClient = ApiClient()
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                for ((sender, parts) in bySender) {
                    val body = parts.joinToString("") { it.messageBody }
                    val timestampMs = parts.first().timestampMillis
                    val serverId = apiClient.postNotification(
                        endpoint = settings.endpoint,
                        userId = settings.userId,
                        title = sender,
                        body = body,
                        timestampMs = timestampMs,
                        sourcePackage = SMS_SOURCE_PACKAGE,
                        appName = "SMS"
                    )
                    if (serverId != null) {
                        Log.d(TAG, "Synced SMS from $sender → $serverId")
                    } else {
                        Log.w(TAG, "Server rejected SMS from $sender")
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
