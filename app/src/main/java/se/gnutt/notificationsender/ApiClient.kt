package se.gnutt.notificationsender

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * POST /api/notifications — stores a new notification and fans out push messages.
     * Returns the server-assigned notification ID, or null on failure.
     */
    fun postNotification(
        endpoint: String,
        userId: String,
        title: String,
        body: String,
        timestampMs: Long,
        sourcePackage: String,
        appName: String? = null,
        icon: String? = null,
        actions: List<Pair<Int, String>>? = null,
        messages: List<NotificationMessage>? = null,
        isSilent: Boolean = false
    ): String? {
        val payload = JSONObject().apply {
            put("userId", userId)
            put("title", title)
            put("body", body)
            put("timestamp", isoFormat.format(Date(timestampMs)))
            put("sourcePackage", sourcePackage)
            if (appName != null) put("appName", appName)
            if (icon != null) put("icon", icon)
            put("isSilent", isSilent)
            if (!actions.isNullOrEmpty()) {
                val actionsArray = org.json.JSONArray()
                for ((semanticAction, actionTitle) in actions) {
                    actionsArray.put(JSONObject().apply {
                        put("semanticAction", semanticAction)
                        put("title", actionTitle)
                    })
                }
                put("actions", actionsArray)
            }
            if (!messages.isNullOrEmpty()) {
                val messagesArray = org.json.JSONArray()
                for (msg in messages) {
                    messagesArray.put(JSONObject().apply {
                        if (msg.sender != null) put("sender", msg.sender)
                        put("text", msg.text)
                        put("timestamp", isoFormat.format(Date(msg.timestampMs)))
                        if (msg.senderIcon != null) put("senderIcon", msg.senderIcon)
                    })
                }
                put("messages", messagesArray)
            }
        }

        val request = Request.Builder()
            .url("$endpoint/api/notifications")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val responseJson = JSONObject(response.body?.string() ?: return null)
            responseJson.optString("id").takeIf { it.isNotBlank() }
        }
    }

    /**
     * DELETE /api/notifications/:id — removes the notification from the server.
     */
    fun deleteNotification(
        endpoint: String,
        userId: String,
        notificationId: String
    ): Boolean {
        return try {
            val request = Request.Builder()
                .url("$endpoint/api/notifications/$notificationId?userId=$userId")
                .delete()
                .build()
            client.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * GET /api/notifications — returns full notification objects from the server.
     */
    fun getNotifications(endpoint: String, userId: String): List<ServerNotification>? {
        return try {
            val request = Request.Builder()
                .url("$endpoint/api/notifications?userId=$userId")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val array = org.json.JSONArray(response.body?.string() ?: return null)
                (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    ServerNotification(
                        id = obj.getString("id"),
                        actionTaken = obj.optString("actionTaken").takeIf { it.isNotBlank() },
                        actionResponse = obj.optString("actionResponse").takeIf { it.isNotBlank() },
                        actionDispatched = obj.optBoolean("actionDispatched", false)
                    )
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * POST /api/notifications/:id/actions/dispatched — acknowledges that the action has been
     * fired on the device. This keeps the server entry visible in the web UI after an action.
     */
    fun postActionDispatched(
        endpoint: String,
        userId: String,
        notificationId: String
    ): Boolean {
        return try {
            val payload = JSONObject().apply { put("userId", userId) }
            val request = Request.Builder()
                .url("$endpoint/api/notifications/$notificationId/actions/dispatched")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * POST /api/device-tokens — registers or updates the FCM token for push delivery.
     * Returns null on success, or an error string on failure.
     */
    fun registerFcmToken(endpoint: String, userId: String, token: String): String? {
        return try {
            val payload = JSONObject().apply {
                put("userId", userId)
                put("token", token)
            }
            val request = Request.Builder()
                .url("$endpoint/api/device-tokens")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) null
                else "HTTP ${response.code}: ${response.body?.string()?.take(200)}"
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /**
     * GET /api/users/:userId — validates that the userId exists on the server.
     * Returns null on success, or an error string describing the failure.
     */
    fun validateUser(endpoint: String, userId: String): String? {
        return try {
            val request = Request.Builder()
                .url("$endpoint/api/users/$userId?userId=$userId")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) null
                else "HTTP ${response.code}: ${response.body?.string()?.take(200)}"
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }
}

data class ServerNotification(
    val id: String,
    val actionTaken: String?,
    val actionResponse: String?,
    val actionDispatched: Boolean = false
)

data class NotificationMessage(
    val sender: String?,
    val text: String,
    val timestampMs: Long,
    val senderIcon: String?  // base64 PNG, may be null
)
