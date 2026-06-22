# Notification Sender

An Android app that keeps your phone's notifications in sync with a web service. Notifications appear on the web as they arrive, and dismissing them on either side dismisses them on the other.

**Backend project:** [daGnutt/WebNotifications](https://github.com/daGnutt/WebNotifications)

---

## Features

- **Phone → server:** Every new notification is posted to the API with title, body, app name, icon, semantic actions (Reply, Mark as Read, etc.), and a `isSilent` flag indicating whether the notification channel has no sound/vibration
- **Server → phone:** Notifications dismissed via the web interface are cancelled on the phone (via FCM push or 10-second poll fallback)
- **Orphan cleanup:** Every 10 seconds, any server entry whose notification is no longer active on the phone is automatically deleted (phone is the source of truth)
- **Actions from web:** Tapping an action (e.g. Like, Reply) in the web UI fires the corresponding Android notification action on the phone, including reply text for RemoteInput-based reply actions. The notification is left on the device afterwards — the source app updates or dismisses it as appropriate (e.g. Teams replaces it with a sent receipt)
- **Phone → server dismissal:** Swiping away a notification on the phone removes it from the server
- **Startup sync:** On connect, orphaned server entries are cleaned up and all active notifications are (re-)posted to the server
- **Server restart resilience:** The server stores notifications in memory only. If a restart is detected (poll returns an empty list while the app has local mappings), the app automatically resyncs all active notifications so the web interface stays current
- **FCM resync:** The server can send a `resync` FCM message (e.g. after a restart or token refresh) to trigger an immediate full sync of all active notifications
- **Media player control:** All active `MediaSession` players on the phone are reported to the server with album art, title, artist, playback state, and position. The web interface shows dedicated player cards (album art + seek bar + transport controls). Play/pause/next/previous/seek commands are dispatched back to the phone via FCM and applied directly to the `MediaSession` — no notification required
- **QR code setup:** Scan a QR code from the web interface to configure endpoint and user ID instantly
- **Unredacted SMS body:** When a notification arrives from the default SMS app, the actual SMS body **and sender** are read from the Telephony content provider instead of the notification extras. On Android 15+, the OS redacts sensitive notifications (OTPs, verification codes) before the notification listener sees them — both title and body are replaced with generic placeholders. Reading directly from the SMS database bypasses this redaction, restoring the real OTP content and the sender's phone number.
- **Wi-Fi only mode:** Optional toggle to block all app network calls when the device is not on Wi-Fi/Ethernet. When enabled and the device leaves Wi-Fi, a silent status card ("Sync paused — not on Wi-Fi") is posted to the server so the web UI knows syncing is paused. The card is automatically removed and a full sync is triggered when Wi-Fi reconnects.

---

## Setup

### 1. Install the app

Build from source (see [Building](#building)) or sideload the APK.

### 2. Grant notification access

Open the app and tap **Grant Notification Access**. Enable *Notification Sender* in the system notification listener settings. Return to the app — the status indicator should turn green.

### 3. Configure the endpoint

Enter your server URL and User GUID, then tap **Save & Verify**. Alternatively, tap **Scan QR Code** and scan the QR code from the web interface.

The User GUID is the `userId` (UUID) returned when you log in to the [web service](https://github.com/daGnutt/WebNotifications). It is **not** your username.

---

## QR Code Format

The web interface can generate a QR code for quick setup. The payload is a JSON object:

```json
{
  "serverUrl": "https://notifications.example.com",
  "userId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
}
```

---

## Building

### Prerequisites

- Android SDK (API 34), build-tools 34.0.0
- JDK bundled with Android Studio (or any JDK 17+)

```bash
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android/sdk
export PATH=$JAVA_HOME/bin:$PATH
```

### Debug build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Release build

A keystore (`release.jks`) is required. Generate one if you don't have it:

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias notificationsender \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=NotificationSender, O=YourOrg, C=SE" \
  -storepass android -keypass android
```

Then build:

```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

### Install via ADB

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Architecture

| Component | Role |
|-----------|------|
| `MainActivity` | Setup UI — endpoint URL, user GUID, QR scan, listener status |
| `NotificationSyncService` | `NotificationListenerService` — all sync logic, runs in background |
| `MediaSessionMonitor` | Monitors active `MediaSession`s; reports state/art to server, handles media control FCM |
| `ApiClient` | OkHttp wrapper for all REST calls |
| `SettingsManager` | SharedPreferences wrapper; stores config, Wi-Fi-only preference, and a `sbn.key → serverId` map |
| `QrScanActivity` | Full-screen CameraX + ML Kit QR scanner |

### API endpoints used

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/notifications` | Post a notification |
| `DELETE` | `/api/notifications/:id` | Remove a notification |
| `GET` | `/api/notifications` | Poll for server-side dismissals/actions (fallback) |
| `GET` | `/api/users/:userId` | Verify user on setup |
| `POST` | `/api/device-tokens` | Register FCM token for push delivery |
| `PUT` | `/api/media-sessions/:sessionId` | Upsert a media session (state + album art) |
| `DELETE` | `/api/media-sessions/:sessionId` | Remove a media session when it ends |

The server sends FCM data messages to the phone when a notification is dismissed or an action is taken via the web UI — see [WebNotifications API_DOCS](https://github.com/daGnutt/WebNotifications/blob/main/API_DOCS.md) for the message format.

The server can also send a `resync` FCM message (e.g. after a restart or when a new device token is registered) to trigger a full re-POST of all currently active phone notifications.

For media control, the server sends a `mediaControl` FCM message with `sessionId`, `mediaAction`, and optional `positionMs`.

---

## Requirements

- Android 8.0+ (API 26)
- Notification listener permission
- Camera permission (for QR scanning only)
- `ACCESS_NETWORK_STATE` permission (used by the Wi-Fi-only toggle)
- `RECEIVE_SENSITIVE_NOTIFICATIONS` permission declared (note: this is a privileged system-only permission; it has no effect for third-party apps but is kept for forward compatibility)
- `READ_SMS` permission — **runtime-requested** via a prompt in `MainActivity`; reads unredacted SMS body and sender from the Telephony content provider to bypass Android 15's OTP redaction. Without this permission, OTP/sensitive SMS notifications will be forwarded with redacted content.
