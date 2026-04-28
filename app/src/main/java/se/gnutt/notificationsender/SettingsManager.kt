package se.gnutt.notificationsender

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class SettingsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "NotificationSenderPrefs"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_USER_ID = "userId"
        private const val KEY_NOTIFICATION_MAP = "notificationMap"
        private const val KEY_FCM_TOKEN = "fcmToken"
        private const val KEY_APP_META_CACHE = "appMetaCache"
        private const val KEY_PENDING_FCM = "pendingFcmCommands"
    }

    data class AppMeta(val name: String, val icon: String?)

    data class PendingFcmCommand(
        val id: String,
        val type: String,           // "dismiss" | "action"
        val serverId: String,
        val actionTaken: String?,
        val actionResponse: String?
    )

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
        val cache = readJsonPref(KEY_APP_META_CACHE)
        if (!cache.has(packageName)) return null
        val obj = cache.optJSONObject(packageName) ?: return null
        val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return null
        val icon = obj.optString("icon").takeIf { it.isNotBlank() }
        return AppMeta(name, icon)
    }

    @Synchronized
    fun storeAppMeta(packageName: String, name: String, icon: String?) {
        val cache = readJsonPref(KEY_APP_META_CACHE)
        cache.put(packageName, JSONObject().apply {
            put("name", name)
            if (icon != null) put("icon", icon)
        })
        saveJsonPref(KEY_APP_META_CACHE, cache)
    }

    @Synchronized
    fun storePendingFcmCommand(cmd: PendingFcmCommand) {
        val arr = readJsonArrayPref(KEY_PENDING_FCM)
        arr.put(JSONObject().apply {
            put("id", cmd.id)
            put("type", cmd.type)
            put("serverId", cmd.serverId)
            if (cmd.actionTaken != null) put("actionTaken", cmd.actionTaken)
            if (cmd.actionResponse != null) put("actionResponse", cmd.actionResponse)
        })
        saveJsonArrayPref(KEY_PENDING_FCM, arr)
    }

    @Synchronized
    fun removePendingFcmCommand(id: String) {
        val arr = readJsonArrayPref(KEY_PENDING_FCM)
        val filtered = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("id") != id) filtered.put(obj)
        }
        saveJsonArrayPref(KEY_PENDING_FCM, filtered)
    }

    @Synchronized
    fun drainPendingFcmCommands(): List<PendingFcmCommand> {
        val arr = readJsonArrayPref(KEY_PENDING_FCM)
        if (arr.length() == 0) return emptyList()
        val result = (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = obj.optString("type").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val serverId = obj.optString("serverId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PendingFcmCommand(
                id = id,
                type = type,
                serverId = serverId,
                actionTaken = obj.optString("actionTaken").takeIf { it.isNotBlank() },
                actionResponse = obj.optString("actionResponse").takeIf { it.isNotBlank() }
            )
        }
        prefs.edit().remove(KEY_PENDING_FCM).apply()
        return result
    }

    private fun readMap(): JSONObject = readJsonPref(KEY_NOTIFICATION_MAP)

    private fun saveMap(map: JSONObject) = saveJsonPref(KEY_NOTIFICATION_MAP, map)

    private fun readJsonPref(key: String): JSONObject {
        val json = prefs.getString(key, "{}") ?: "{}"
        return try { JSONObject(json) } catch (_: Exception) { JSONObject() }
    }

    private fun saveJsonPref(key: String, obj: JSONObject) {
        prefs.edit().putString(key, obj.toString()).apply()
    }

    private fun readJsonArrayPref(key: String): JSONArray {
        val json = prefs.getString(key, "[]") ?: "[]"
        return try { JSONArray(json) } catch (_: Exception) { JSONArray() }
    }

    private fun saveJsonArrayPref(key: String, arr: JSONArray) {
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
