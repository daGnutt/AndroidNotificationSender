# Copilot Instructions

## Build & Deploy

**Prerequisites** — set these before building:
```bash
export JAVA_HOME=/home/gnutt/Downloads/android-studio-panda3-linux/android-studio/jbr
export ANDROID_HOME=~/Android/Sdk
export PATH=$JAVA_HOME/bin:$PATH
```

**Build debug APK:**
```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
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
```

**`SettingsManager`** — SharedPreferences wrapper that stores endpoint, userId, a JSON map of `sbn.key → serverId`, and an app metadata cache (`packageName → { name, icon }`). All map/cache methods are `@Synchronized`. The notification map is the single source of truth for which phone notifications have been synced to the server.

**App metadata cache** — `SettingsManager` persists `appName` and `icon` (base64 PNG) per `packageName` via `storeAppMeta()` / `getAppMeta()`. In `postSbn()`, fresh values from the PackageManager are written to the cache on success; cached values are used as fallback when `getAppName()` falls back to the package name or `getAppIconBase64()` returns null. This ensures notifications are posted with correct display names and icons even during early service startup after a reinstall (when PackageManager rendering can silently fail).

**`ApiClient`** — pure OkHttp, no Android context. All methods are blocking (call on IO dispatcher). Returns `null`/`false` on failure rather than throwing.

## Documentation

**Always update documentation alongside code changes.** When modifying behaviour, adding features, or fixing bugs, update all relevant docs in the same commit:
- `README.md` — user-facing features, architecture overview, API table
- `API_DOCS.md` — API endpoint details
- `.github/copilot-instructions.md` — conventions, architecture notes, key behaviours

## Key Conventions

**Notification key (`sbn.key`)** is the stable identifier used as the local map key. It's a composite string like `"0|com.package|123|null|10000"` — consistent between `onNotificationPosted` and `onNotificationRemoved`.

**Update = delete + re-post:** `onNotificationPosted` fires on every notification change, not just creation. Always check if the key is already mapped; if so, delete the old server entry before posting a new one. Skipping this creates duplicate server entries with orphaned mappings.

**Poll loop ordering matters:** In `pollServerDismissals`, always call `settings.removeNotificationMapping(key)` **before** `cancelNotification(key)`. If you cancel first, `onNotificationRemoved` fires and tries to DELETE an already-gone server entry.

**Server restart detection:** The backend stores notifications in memory only — a restart wipes all entries. In `pollServerDismissals`, if the server returns an empty list while local mappings exist, treat it as a restart and call `fullSync()` instead of cancelling phone notifications. This prevents all phone notifications from being dismissed on every server restart.

**Notification body priority:** Prefer `android.bigText` over `android.text` when extracting body content from `sbn.notification.extras`. For notifications from the default SMS app, the body is fetched from the `Telephony.Sms` content provider (`fetchSmsBody()`) instead of notification extras — this provides unredacted content including OTP codes. Falls back to notification extras if the query finds nothing.

**FCM message types:** `FcmService.onMessageReceived()` handles `"dismiss"`, `"action"`, and `"resync"`. Dismiss and action messages are forwarded as local broadcasts to `NotificationSyncService` (which holds the `NotificationListenerService` binding). Resync also broadcasts to `NotificationSyncService`, which calls `fullSync()`.

**Action dispatch flow:** When an "action" FCM message arrives, `handleActionRequest` (1) removes the local mapping, (2) fires the intent on the device, then (3) calls `POST /api/notifications/:id/actions/dispatched` to acknowledge dispatch. The server entry is **not** deleted — it is kept so the web UI retains a history record. The mapping is removed first so that when the source app subsequently dismisses the notification, `onNotificationRemoved` finds no mapping and exits early (no spurious DELETE). The fallback poll loop only dispatches an action when `actionTaken != null && !actionDispatched` to avoid re-firing after a successful dispatch.

**SMS content:** `SmsReceiver` has been removed. SMS notifications are handled by `NotificationSyncService` like any other notification, but `postSbn()` detects the default SMS app and fetches the actual body from the Telephony content provider. Requires `READ_SMS` permission (same permission group as `RECEIVE_SMS` — typically auto-granted if user has an SMS app).

**QR payload format:**
```json
{"serverUrl": "https://...", "userId": "uuid"}
```
Parsed in `QrScanActivity.parseAndReturn()`, returned via `Intent` extras to `MainActivity`.

**No viewBinding** — uses `findViewById` throughout. Do not re-enable viewBinding without also updating all activity classes.

## API Summary

Live server: configured by the user at setup time

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `POST` | `/api/notifications` | `userId` in body | Post a notification |
| `DELETE` | `/api/notifications/:id` | `?userId=` query param | Remove a notification |
| `GET` | `/api/notifications` | `?userId=` query param | List all notifications (returns `id` per item) |
| `GET` | `/api/users/:userId` | — | Verify user exists (404 = not found) |

`userId` is a UUID obtained from `POST /api/auth` (username + password). It is **not** the username — it's the `userId` field in the response.

## Permissions & Setup

The app requires these permissions:
- `INTERNET` — declared in manifest, granted automatically
- Notification Listener — must be granted manually via Settings → Notification Access. The UI shows a button to open that settings screen when not granted.
- `CAMERA` — runtime permission, requested when user taps "Scan QR"
- `READ_SMS` — declared in manifest, auto-granted alongside any SMS app permission grant; used by `fetchSmsBody()` to read unredacted SMS content from the Telephony content provider
