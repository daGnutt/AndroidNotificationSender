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
        actions: List<Pair<Int, String>>? = null
    ): String? {
        val payload = JSONObject().apply {
            put("userId", userId)
            put("title", title)
            put("body", body)
            put("timestamp", isoFormat.format(Date(timestampMs)))
            put("sourcePackage", sourcePackage)
            if (appName != null) put("appName", appName)
            if (icon != null) put("icon", icon)
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
     * GET /api/notifications — returns the set of notification IDs currently on the server.
     */
    fun getNotificationIds(endpoint: String, userId: String): Set<String>? {
        return try {
            val request = Request.Builder()
                .url("$endpoint/api/notifications?userId=$userId")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val array = org.json.JSONArray(response.body?.string() ?: return null)
                (0 until array.length()).map { array.getJSONObject(it).getString("id") }.toSet()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * GET /api/users/:userId — validates that the userId exists on the server.
     * Returns null on success, or an error string describing the failure.
     */
    fun validateUser(endpoint: String, userId: String): String? {
        return try {
            val request = Request.Builder()
                .url("$endpoint/api/users/$userId")
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
