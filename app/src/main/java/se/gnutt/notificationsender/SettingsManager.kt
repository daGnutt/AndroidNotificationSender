package se.gnutt.notificationsender

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

class SettingsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "NotificationSenderPrefs"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_USER_ID = "userId"
        private const val KEY_NOTIFICATION_MAP = "notificationMap"
        private const val KEY_FCM_TOKEN = "fcmToken"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var endpoint: String
        get() = prefs.getString(KEY_ENDPOINT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ENDPOINT, value).apply()

    var userId: String
        get() = prefs.getString(KEY_USER_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var fcmToken: String?
        get() = prefs.getString(KEY_FCM_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_FCM_TOKEN, value).apply()

    val isConfigured: Boolean
        get() = endpoint.isNotBlank() && userId.isNotBlank()

    @Synchronized
    fun getAllMappings(): Map<String, String> {
        val map = readMap()
        return map.keys().asSequence().associateWith { map.getString(it) }
    }

    @Synchronized
    fun storeNotificationMapping(notificationKey: String, serverId: String) {
        val map = readMap()
        map.put(notificationKey, serverId)
        saveMap(map)
    }

    @Synchronized
    fun getNotificationServerId(notificationKey: String): String? {
        val map = readMap()
        return if (map.has(notificationKey)) map.getString(notificationKey) else null
    }

    @Synchronized
    fun removeNotificationMapping(notificationKey: String) {
        val map = readMap()
        map.remove(notificationKey)
        saveMap(map)
    }

    @Synchronized
    fun getNotificationKeyByServerId(serverId: String): String? {
        val map = readMap()
        return map.keys().asSequence().firstOrNull { map.getString(it) == serverId }
    }

    private fun readMap(): JSONObject {
        val json = prefs.getString(KEY_NOTIFICATION_MAP, "{}") ?: "{}"
        return try {
            JSONObject(json)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun saveMap(map: JSONObject) {
        prefs.edit().putString(KEY_NOTIFICATION_MAP, map.toString()).apply()
    }
}
