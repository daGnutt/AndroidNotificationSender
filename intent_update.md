# Handling Action Replies from the Web UI

When a user replies to or acts on a notification in the web interface, the action is saved back to the notification record. The Android app should poll for pending actions and dispatch them (e.g. send an SMS reply, fire a system intent).

## 1 — Send notifications with `sourcePackage`

Include `sourcePackage` (your app's package name) when sending so you can route the reply back correctly:

```json
POST /api/notifications
{
  "userId": "<guid>",
  "title": "SMS from Alice",
  "body": "Are you coming tonight?",
  "sourcePackage": "com.example.smsapp",
  "actions": [{ "type": "reply", "title": "Reply" }]
}
```

## 2 — Poll for pending actions

```
GET {serverUrl}/api/users/{userId}/notifications
```

Filter the response for notifications where `actionTaken` is set and `actionDispatched` is not `true`:

```kotlin
val pending = notifications.filter { n ->
    n.actionTaken != null && n.actionDispatched != true
}
```

Fields available on each pending notification:

| Field | Description |
|-------|-------------|
| `id` | Notification ID (required for the ack call) |
| `actionTaken` | Action key chosen by the user (e.g. `"reply"`) |
| `actionResponse` | Typed reply text, if any |
| `sourcePackage` | Your app's package name to route the action back |

## 3 — Dispatch the action

Use `actionTaken` and `actionResponse` to perform the actual work — send the SMS reply, fire a RemoteInput intent, etc.

## 4 — Acknowledge dispatch

After successfully dispatching, mark the action as done so it isn't re-processed on the next poll:

```
POST {serverUrl}/api/notifications/{id}/actions/dispatched
Content-Type: application/json

{ "userId": "<guid>" }
```

Returns `{ "success": true }`.

> If the user takes a new action on the same notification later (e.g. replies again), `actionDispatched` is cleared automatically so the cycle repeats.
