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
        private const val KEY_APP_META_CACHE = "appMetaCache"
    }

    data class AppMeta(val name: String, val icon: String?)

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

    @Synchronized
    fun getAppMeta(packageName: String): AppMeta? {
        val cache = readAppMetaCache()
        if (!cache.has(packageName)) return null
        val obj = cache.optJSONObject(packageName) ?: return null
        val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return null
        val icon = obj.optString("icon").takeIf { it.isNotBlank() }
        return AppMeta(name, icon)
    }

    @Synchronized
    fun storeAppMeta(packageName: String, name: String, icon: String?) {
        val cache = readAppMetaCache()
        cache.put(packageName, JSONObject().apply {
            put("name", name)
            if (icon != null) put("icon", icon)
        })
        prefs.edit().putString(KEY_APP_META_CACHE, cache.toString()).apply()
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

    private fun readAppMetaCache(): JSONObject {
        val json = prefs.getString(KEY_APP_META_CACHE, "{}") ?: "{}"
        return try {
            JSONObject(json)
        } catch (_: Exception) {
            JSONObject()
        }
    }
}
