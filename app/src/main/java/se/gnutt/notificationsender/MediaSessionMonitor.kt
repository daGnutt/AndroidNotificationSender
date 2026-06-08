package se.gnutt.notificationsender

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Monitors all active [MediaSession]s on the device and reports their state to the server.
 *
 * Requires the [android.service.notification.NotificationListenerService] permission to be
 * active — that permission implicitly grants MEDIA_CONTENT_CONTROL, which is required by
 * [MediaSessionManager.getActiveSessions].
 *
 * Call [start] once the NotificationListenerService is connected, [stop] on destroy.
 */
class MediaSessionMonitor(
    private val context: Context,
    private val notificationListenerComponent: ComponentName,
    private val scope: CoroutineScope,
    private val settings: SettingsManager,
    private val apiClient: ApiClient
) {

    companion object {
        private const val TAG = "MediaSessionMonitor"
        private const val ALBUM_ART_SIZE = 256
    }

    private val mediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    // Map of sessionKey → registered MediaController.Callback, so we can unregister on remove.
    private val activeCallbacks = ConcurrentHashMap<String, MediaController.Callback>()

    // Map of sessionKey → MediaController, for use by FCM mediaControl handler.
    private val activeControllers = ConcurrentHashMap<String, MediaController>()

    // Pending delayed re-report jobs, keyed by sessionKey. Cancelled if a newer report arrives first.
    private val pendingReports = ConcurrentHashMap<String, Job>()

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            onActiveSessionsChanged(controllers ?: emptyList())
        }

    fun start() {
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                notificationListenerComponent
            )
            // Snapshot current sessions
            val current = mediaSessionManager.getActiveSessions(notificationListenerComponent)
            onActiveSessionsChanged(current)
            Log.i(TAG, "MediaSessionMonitor started, ${current.size} active session(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaSessionMonitor: ${e.message}")
        }
    }

    fun stop() {
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            activeCallbacks.forEach { (key, callback) ->
                activeControllers[key]?.unregisterCallback(callback)
            }
            activeCallbacks.clear()
            activeControllers.clear()
            pendingReports.values.forEach { it.cancel() }
            pendingReports.clear()
            Log.i(TAG, "MediaSessionMonitor stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaSessionMonitor: ${e.message}")
        }
    }

    /** Returns the [MediaController] for [sessionKey], or null if not currently tracked. */
    fun getController(sessionKey: String): MediaController? = activeControllers[sessionKey]

    private fun onActiveSessionsChanged(controllers: List<MediaController>) {
        val newKeys = controllers.map { sessionKeyFor(it) }.toSet()
        val oldKeys = activeCallbacks.keys.toSet()

        // Unregister callbacks and delete server entries for sessions that ended
        for (removedKey in oldKeys - newKeys) {
            val callback = activeCallbacks.remove(removedKey)
            activeControllers.remove(removedKey)?.let { ctrl ->
                if (callback != null) ctrl.unregisterCallback(callback)
            }
            pendingReports.remove(removedKey)?.cancel()
            Log.d(TAG, "Media session ended: $removedKey")
            scope.launch { deleteSession(removedKey) }
        }

        // Register callbacks for new sessions
        for (controller in controllers) {
            val key = sessionKeyFor(controller)
            if (activeCallbacks.containsKey(key)) continue  // already tracking
            registerController(controller, key)
            scope.launch { reportSession(controller) }
        }
    }

    private fun registerController(controller: MediaController, key: String) {
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                // Cancel any pending delayed re-report since we now have fresh metadata
                pendingReports.remove(key)?.cancel()
                scope.launch { reportSession(controller) }
            }
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                scope.launch { reportSession(controller) }
                // Schedule a delayed re-report to catch metadata that arrives after the state change.
                // This handles music apps that update playback state before updating metadata.
                pendingReports.remove(key)?.cancel()
                pendingReports[key] = scope.launch {
                    delay(1500)
                    if (activeControllers.containsKey(key)) reportSession(controller)
                }
            }
            override fun onSessionDestroyed() {
                activeCallbacks.remove(key)
                activeControllers.remove(key)
                pendingReports.remove(key)?.cancel()
                scope.launch { deleteSession(key) }
            }
        }
        // Callbacks must be registered on the main thread
        scope.launch(Dispatchers.Main) {
            try {
                controller.registerCallback(callback)
                activeCallbacks[key] = callback
                activeControllers[key] = controller
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register callback for $key: ${e.message}")
            }
        }
    }

    private suspend fun reportSession(controller: MediaController) {
        if (!settings.isConfigured) return
        val key = sessionKeyFor(controller)
        val metadata = controller.metadata
        val state = controller.playbackState

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        val playbackState = when (state?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED  -> "paused"
            else                        -> "stopped"
        }
        val positionMs = state?.position ?: 0L

        val packageName = controller.packageName
        val appName = getAppName(packageName)

        val appIcon = getAppIconBase64(packageName)

        val albumArt = metadata?.let { meta ->
            val bitmap = meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            bitmap?.let { bitmapToBase64(it, ALBUM_ART_SIZE) }
        }

        val data = MediaSessionData(
            packageName = packageName,
            appName = appName,
            appIcon = appIcon,
            title = title,
            artist = artist,
            album = album,
            albumArt = albumArt,
            playbackState = playbackState,
            positionMs = positionMs,
            durationMs = durationMs
        )

        val ok = apiClient.putMediaSession(settings.endpoint, settings.userId, key, data)
        if (ok) {
            Log.d(TAG, "Reported session $key: $playbackState \"$title\"")
        } else {
            Log.w(TAG, "Failed to report session $key")
        }
    }

    private suspend fun deleteSession(key: String) {
        if (!settings.isConfigured) return
        apiClient.deleteMediaSession(settings.endpoint, settings.userId, key)
        Log.d(TAG, "Deleted session $key from server")
    }

    /**
     * Derives a stable string key for a [MediaController]. Uses [packageName] since virtually
     * all apps expose exactly one session. In the rare case of multiple sessions from the same
     * package, disambiguates with a ":index" suffix based on insertion order.
     */
    private fun sessionKeyFor(controller: MediaController): String {
        val pkg = controller.packageName
        // Check if we already have a key assigned for this controller token
        for ((key, existing) in activeControllers) {
            if (existing.sessionToken == controller.sessionToken) return key
        }
        // Assign a new key: "pkg" if none exists yet, "pkg:N" for collisions
        val existing = activeControllers.keys.filter { it == pkg || it.startsWith("$pkg:") }
        return if (existing.isEmpty()) pkg else "$pkg:${existing.size}"
    }

    private fun getAppName(packageName: String): String {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private suspend fun getAppIconBase64(packageName: String): String? =
        withContext(Dispatchers.Main) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, 96, 96)
                drawable.draw(canvas)
                bitmapToBase64(bitmap, 96)
            } catch (_: Exception) { null }
        }

    /** Scales [bitmap] to fit within [maxSize] px (preserving aspect ratio) and returns base64 PNG. */
    private fun bitmapToBase64(bitmap: Bitmap, maxSize: Int): String {
        val scaled = if (bitmap.width > maxSize || bitmap.height > maxSize) {
            val ratio = maxSize.toFloat() / maxOf(bitmap.width, bitmap.height)
            val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
            val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 90, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }
}
