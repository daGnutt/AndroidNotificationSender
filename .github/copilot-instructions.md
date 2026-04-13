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
```

**`SettingsManager`** — SharedPreferences wrapper that stores endpoint, userId, and a JSON map of `sbn.key → serverId`. All map methods are `@Synchronized`. The JSON map is the single source of truth for which phone notifications have been synced to the server.

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

**Notification body priority:** Prefer `android.bigText` over `android.text` when extracting body content from `sbn.notification.extras`.

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

The app requires two permissions:
- `INTERNET` — declared in manifest, granted automatically
- Notification Listener — must be granted manually via Settings → Notification Access. The UI shows a button to open that settings screen when not granted.
- `CAMERA` — runtime permission, requested when user taps "Scan QR"
