# Copilot Instructions

## Build & Deploy

**Prerequisites** — set these before building:
```bash
export JAVA_HOME=/home/gnutt/Downloads/android-studio-quail1/jbr
export ANDROID_HOME=~/Android/Sdk
export PATH=$JAVA_HOME/bin:$PATH
```

**Build debug APK:**
```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

**Build + install in one step (all connected devices):**
```bash
./deploy.sh
```

**Build release APK** (requires `release.jks` in repo root — signing credentials in `app/build.gradle.kts`):
```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

**Install to connected phone:**
```bash
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If ADB can't find the device:
```bash
~/Android/Sdk/platform-tools/adb kill-server && ~/Android/Sdk/platform-tools/adb start-server
```

**Device serial:** `5A160DLCH005TM`  
**Target API:** Android 34, minSdk 26

## Architecture

The app has two main entry points that run independently:

1. **`MainActivity`** — setup UI only (endpoint URL + user GUID). Saves to `SettingsManager`, calls `ApiClient.validateUser()` to verify. Also launches `QrScanActivity` for QR-based setup.

2. **`NotificationSyncService`** — a `NotificationListenerService` that runs as a background service. Android binds it automatically once the user grants notification listener permission. It manages all sync logic with **no UI involvement**.

Data flow:
```
Phone notification posted   → onNotificationPosted  → ApiClient.postNotification  → store mapping
Phone notification removed  → onNotificationRemoved → ApiClient.deleteNotification (if mapped)
Server entry deleted        → pollServerDismissals (10s loop) → cancelNotification(key)
App started (reconnect)     → onListenerConnected   → reconcile orphaned entries + backfill active
FCM "resync" received       → FcmService broadcast  → NotificationSyncService.fullSync()
FCM token rotated           → onNewToken            → re-register token; server sends "resync" back
Media session state change  → MediaController.Callback → ApiClient.putMediaSession  → server card
Media session ended         → onSessionDestroyed    → ApiClient.deleteMediaSession
FCM "mediaControl" received → FcmService broadcast  → MediaController.transportControls dispatch
```

**`MediaSessionMonitor`** — started in `onListenerConnected()`, stopped in `onDestroy()`. Uses `MediaSessionManager.getActiveSessions(componentName)` (no extra permission — the active `NotificationListenerService` implicitly grants `MEDIA_CONTENT_CONTROL`). Registers `OnActiveSessionsChangedListener` to track session lifecycle and a `MediaController.Callback` per session for metadata/state change events. Session key = `packageName` (disambiguated with `:index` for the rare multi-session edge case). Album art is scaled to max 128 px before base64 encoding. `MediaController.Callback` fires on the main thread — all network calls are dispatched to `scope` (IO dispatcher).

**`SettingsManager`** — SharedPreferences wrapper that stores endpoint, userId, a `wifiOnly` toggle, a `wifiOfflineServerId` (server ID of the Wi-Fi offline status notification, or null), a JSON map of `sbn.key → serverId`, and an app metadata cache (`packageName → { name, icon }`). All map/cache methods are `@Synchronized`. The notification map is the single source of truth for which phone notifications have been synced to the server.

**App metadata cache** — `SettingsManager` persists `appName` and `icon` (base64 PNG) per `packageName` via `storeAppMeta()` / `getAppMeta()`. In `postSbn()`, fresh values from the PackageManager are written to the cache on success; cached values are used as fallback when `getAppName()` falls back to the package name or `getAppIconBase64()` returns null. This ensures notifications are posted with correct display names and icons even during early service startup after a reinstall (when PackageManager rendering can silently fail).

**`ApiClient`** — pure OkHttp, no Android context. All methods are blocking (call on IO dispatcher). Returns `null`/`false` on failure rather than throwing.

## Backend

The receiving system is **[daGnutt/WebNotifications](https://github.com/daGnutt/WebNotifications)**. When implementing new features or fixing bugs that touch the API, consult that repo's documentation to ensure the request/response shapes and FCM message formats stay in sync.

## Documentation

**Always update documentation alongside code changes.** When modifying behaviour, adding features, or fixing bugs, update all relevant docs in the same commit:
- `README.md` — user-facing features, architecture overview, API table
- `API_DOCS.md` — API endpoint details
- `.github/copilot-instructions.md` — conventions, architecture notes, key behaviours

## Key Conventions

**Notification key (`sbn.key`)** is the stable identifier used as the local map key. It's a composite string like `"0|com.package|123|null|10000"` — consistent between `onNotificationPosted` and `onNotificationRemoved`.

**Update = delete + re-post:** `onNotificationPosted` fires on every notification change, not just creation. Always check if the key is already mapped; if so, delete the old server entry before posting a new one. Skipping this creates duplicate server entries with orphaned mappings.

**Per-key mutexes (`keyMutexes`)** — prevents race conditions where rapid back-to-back posts for the same key create duplicate server entries. The mutex is intentionally **never removed** from the map; removing it outside the `withLock` block would allow a queued coroutine and a new coroutine to hold separate mutexes for the same key simultaneously, defeating mutual exclusion.

**`recentlyGoneKeys` (duplicate-burst suppression)** — Android 15 OTP SMS notifications fire `onNotificationPosted` 3× within ~22 ms for the same key before auto-dismissing. The sub-second cleanup removes the mapping after the first coroutine posts, so the second and third coroutines see no mapping and post as fresh entries (creating 3 server entries). `recentlyGoneKeys` (a `ConcurrentHashMap<String, Long>`) records keys that went through the "already gone" cleanup path, with the cleanup timestamp. `isRecentlyGone(key)` is checked at the top of the key-mutex block in `onNotificationPosted`; if the key was cleaned up within the last 10 s, the post is suppressed. `markRecentlyGone(key)` is called in the cleanup path immediately after removing the mapping. The 10 s TTL allows re-shows of the same notification key after legitimate user interaction.

**`fullSync` uses key mutexes when re-posting** — the re-post loop in `fullSync` wraps each `postSbn` call in `mutexFor(sbn.key).withLock { if (mapping == null) postSbn(sbn) }`. This prevents `fullSync` and a concurrent `onNotificationPosted` from both posting the same notification: whichever acquires the mutex first stores the mapping, and the other skips.

**Short-lived notification cleanup:** After `postSbn` completes inside `onNotificationPosted`'s coroutine, check whether the notification is still present in `activeNotifications`. If it has already been dismissed (sub-second notifications), `onNotificationRemoved` would have found no mapping and exited early — so the post-post check immediately deletes the server entry, clears the mapping, and calls `markRecentlyGone(key)` to suppress the burst of identical `onNotificationPosted` events that follow. The check uses `stillActive == false` (not `!= true`) to distinguish `false` from the nullable `null` case, defaulting to no-delete when `activeNotifications` throws.

**Mapping removal always comes first:** Always call `settings.removeNotificationMapping(key)` **before** any network call or `cancelNotification(key)` that might trigger `onNotificationRemoved`. This applies everywhere:
- In `pollServerDismissals`: remove mapping before `cancelNotification` (prevents `onNotificationRemoved` from issuing a spurious DELETE for an already-gone entry).
- In `onNotificationRemoved`: remove mapping synchronously (on the calling thread) before `scope.launch` (prevents a concurrent `onNotificationPosted` re-showing the same notification from having its new mapping wiped when the remove runs later on a background thread).
- In `handleActionRequest`: remove mapping before firing the intent (prevents `onNotificationRemoved` from issuing a spurious DELETE when the source app dismisses the notification after the action).

**`DELETE /api/notifications/:id` returns 409 when action is pending:** The server refuses to delete a notification whose `actionTaken` is set but `actionDispatched` is still `false`. `deleteNotification` returns `DeleteResult` (sealed class: `Success`, `NotFound`, `ActionPending`, `Failure(code)`). All callsites handle `ActionPending` as "leave on server as history" — no retry, no re-mapping, no call to `postActionDispatched` for a never-fired action. The trigger scenario is: web UI records an action → FCM is delayed → user dismisses the notification from the phone before the poll loop fires → mapping is removed, server entry stays as history with `actionTaken` set and `actionDispatched` false. In `onNotificationPosted` update-existing path: if the old server entry returns `ActionPending`, skip `removeNotificationMapping` and skip re-posting (the frozen entry cannot be replaced).

**Server restart detection:** The backend stores notifications in memory only — a restart wipes all entries. In `pollServerDismissals`, if the server returns an empty list while local mappings exist, treat it as a restart and call `fullSync()` instead of cancelling phone notifications. This prevents all phone notifications from being dismissed on every server restart.

**Notification body priority:** Prefer `android.bigText` over `android.text` when extracting body content from `sbn.notification.extras`. For notifications from the default SMS app, both the body and sender are fetched from the `Telephony.Sms` content provider via `fetchSms()` (returns `SmsData(body, sender)`) instead of notification extras — this bypasses Android 15's sensitive-notification redaction, which replaces OTP/SMS content with generic placeholder strings before the `NotificationListenerService` ever sees them. Falls back to notification extras if the query finds nothing or `READ_SMS` is not granted.

**FCM message types:** `FcmService.onMessageReceived()` handles `"dismiss"`, `"action"`, `"resync"`, and `"mediaControl"`. Dismiss and action messages are forwarded as local broadcasts to `NotificationSyncService` (which holds the `NotificationListenerService` binding). Resync also broadcasts to `NotificationSyncService`, which calls `fullSync()`. Media control messages (`{ type: "mediaControl", sessionId, mediaAction, positionMs? }`) are forwarded to `NotificationSyncService` which dispatches them to the matching `MediaController.transportControls`.

**Action dispatch flow:** When an "action" FCM message arrives, `handleActionRequest` (1) checks `KeyguardManager.isKeyguardLocked()` — if locked, returns immediately without touching `firedActionIds` so the poll loop or `userPresentReceiver` retries when unlocked; (2) removes the local mapping, (3) fires the intent on the device, then (4) calls `POST /api/notifications/:id/actions/dispatched` to acknowledge dispatch. The server entry is **not** deleted — it is kept so the web UI retains a history record. The mapping is removed first so that when the source app subsequently dismisses the notification, `onNotificationRemoved` finds no mapping and exits early (no spurious DELETE). The fallback poll loop only dispatches an action when `actionTaken != null && !actionDispatched` to avoid re-firing after a successful dispatch. If `fireAction` returns `false` (PendingIntent threw), the `serverId` is removed from `firedActionIds` to allow the next poll cycle to retry.

**Locked-device action deferral:** `handleActionRequest` checks the keyguard state before attempting to fire. If the device is locked, Activity-type PendingIntents would fail (SecurityException or silent no-op), so the attempt is skipped entirely. A `BroadcastReceiver` for `Intent.ACTION_USER_PRESENT` calls `checkPendingActions()` immediately when the device is unlocked, firing all deferred actions without waiting for the scheduled poll cycle (which runs every 5 min when FCM is active).

**SMS content:** `SmsReceiver` has been removed. SMS notifications are handled by `NotificationSyncService` like any other notification, but `postSbn()` detects the default SMS app and enriches the entry by fetching `SmsData(body, sender)` from the Telephony content provider. This is the primary defence against Android 15's OTP redaction, which replaces both title and body with generic strings before the NLS sees them. The enrichment fires as a background coroutine (`enrichSmsBody`) that retries up to 3 × 1 s and then does a delete-and-repost under the key mutex. Requires `READ_SMS` permission — **requested at runtime** in `MainActivity` via a dedicated status row and button. Without it `fetchSms()` returns null and redacted content is forwarded as-is.

**QR payload format:**
```json
{"serverUrl": "https://...", "userId": "uuid"}
```
Parsed in `QrScanActivity.parseAndReturn()`, returned via `Intent` extras to `MainActivity`.

**No viewBinding** — uses `findViewById` throughout. Do not re-enable viewBinding without also updating all activity classes.

**MessagingStyle `messages` array:** For notifications that use `MessagingStyle` (e.g. WhatsApp, Messenger), `postSbn()` extracts the structured message history from `android.messages` (array of `Bundle`) and posts it as a `messages` field. Each entry has `sender`, `text`, `timestamp`, and optional `senderIcon` (base64). On API 33+ use the typed `getParcelableArray(key, Bundle::class.java)` overload to avoid silent deserialization failures.

**`isSilent` flag:** Determined via `NotificationListenerService.currentRanking` (not `NotificationManager.getNotificationChannel()` which only works for the calling package). A notification is silent when its channel importance is below `IMPORTANCE_DEFAULT`. Always use the ranking API for third-party notification channels.

**Wi-Fi only toggle:** `MainActivity` exposes a persisted `wifiOnly` switch. All `ApiClient` methods must respect this policy via the injected network-allowed callback (`isNetworkAllowed(context, settings)`), blocking app traffic whenever Wi-Fi-only is enabled and the active network is not Wi-Fi/Ethernet.

**Wi-Fi offline status notification:** When `wifiOnly` is enabled and the device leaves Wi-Fi/Ethernet, `NotificationSyncService` posts a silent "Sync paused — not on Wi-Fi" notification to the server (via `unrestrictedApiClient` which bypasses the wifiOnly gate, allowing the call over mobile data). The server ID is stored in `settings.wifiOfflineServerId`. When Wi-Fi/Ethernet is restored, `clearOfflineNotification()` deletes the entry and `fullSync()` re-posts all active notifications. A `ConnectivityManager.NetworkCallback` registered in `onCreate()` drives both transitions. On service reconnect (`onListenerConnected()`), the initial state is checked — if wifiOnly is on and no allowed network is present, `postOfflineNotification()` is called before `fullSync()`. The `wifiOfflineServerId` entry is deliberately excluded from the regular `notificationMap` so it is never subject to the normal dismiss/sync logic.

## API Summary

Live server: configured by the user at setup time

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `POST` | `/api/notifications` | `userId` in body | Post a notification |
| `DELETE` | `/api/notifications/:id` | `?userId=` query param | Remove a notification |
| `GET` | `/api/notifications` | `?userId=` query param | List all notifications (poll for dismissals/actions) |
| `GET` | `/api/users/:userId` | `?userId=` query param | Verify user exists (401/403 = not found/wrong user) |
| `POST` | `/api/device-tokens` | `userId` in body | Register/update FCM token for push delivery |
| `POST` | `/api/notifications/:id/actions/dispatched` | `userId` in body | Acknowledge action dispatch (keeps server entry for history) |
| `PUT` | `/api/media-sessions/:sessionId` | `userId` in body | Upsert media session state (metadata + album art + playback state) |
| `DELETE` | `/api/media-sessions/:sessionId` | `?userId=` query param | Remove media session when it ends |

`userId` is a UUID obtained from `POST /api/auth` (username + password). It is **not** the username — it's the `userId` field in the response.

## Permissions & Setup

The app requires these permissions:
- `INTERNET` — declared in manifest, granted automatically
- Notification Listener — must be granted manually via Settings → Notification Access. The UI shows a button to open that settings screen when not granted.
- `CAMERA` — runtime permission, requested when user taps "Scan QR"
- `ACCESS_NETWORK_STATE` — used to enforce the Wi-Fi-only network policy before API calls
- `READ_SMS` — **runtime-requested** in `MainActivity` (shown as a status row with a grant button); used by `fetchSms()` to read unredacted SMS body and sender address from the Telephony content provider, bypassing Android 15's OTP redaction
- `RECEIVE_SENSITIVE_NOTIFICATIONS` — declared in manifest; note this is a privileged/signature permission that has no effect for third-party apps, kept for forward compatibility
