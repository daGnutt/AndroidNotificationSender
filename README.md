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
- **Startup sync:** On connect, orphaned server entries are cleaned up and any active notifications not yet tracked are posted
- **QR code setup:** Scan a QR code from the web interface to configure endpoint and user ID instantly

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
| `ApiClient` | OkHttp wrapper for all REST calls |
| `SettingsManager` | SharedPreferences wrapper; stores config and a `sbn.key → serverId` map |
| `QrScanActivity` | Full-screen CameraX + ML Kit QR scanner |

### API endpoints used

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/notifications` | Post a notification |
| `DELETE` | `/api/notifications/:id` | Remove a notification |
| `GET` | `/api/notifications` | Poll for server-side dismissals/actions (fallback) |
| `GET` | `/api/users/:userId` | Verify user on setup |
| `POST` | `/api/device-tokens` | Register FCM token for push delivery |

The server sends FCM data messages to the phone when a notification is dismissed or an action is taken via the web UI — see [API_DOCS.md](API_DOCS.md) for the message format.

---

## Requirements

- Android 8.0+ (API 26)
- Notification listener permission
- Camera permission (for QR scanning only)
