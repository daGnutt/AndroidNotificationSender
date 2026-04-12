# Web Notifications API Documentation

Base URL: `http://<host>:3000`

---

## Authentication

Most data endpoints require a valid `userId` (a UUID obtained from the auth endpoints). It can be passed as:

- **Query parameter**: `?userId=<uuid>`
- **Request body field**: `"userId": "<uuid>"`

If `userId` is missing or invalid, the server returns `401`.

---

## Endpoints

### Auth

#### `POST /api/auth`

Register a new user or log in to an existing account. This is the single sign-in/register endpoint — it creates the user on first call and verifies the password on subsequent calls.

**Request body**

| Field      | Type   | Required | Description                          |
|------------|--------|----------|--------------------------------------|
| `username` | string | Yes      | Unique username                      |
| `password` | string | Yes      | Plain-text password                  |
| `email`    | string | No       | Email address (used for resets)      |

**Responses**

| Status | Description                        | Body                                                    |
|--------|------------------------------------|---------------------------------------------------------|
| `200`  | Login successful                   | `{ success, created: false, user: { userId, username, email } }` |
| `201`  | Account created                    | `{ success, created: true, user: { userId, username, email } }` |
| `400`  | Missing username or password       | `{ success: false, error }`                             |
| `401`  | Incorrect password                 | `{ success: false, error }`                             |
| `500`  | Server error                       | `{ success: false, error }`                             |

---

#### `POST /api/auth/reset-request`

Send a 6-digit reset code to the user's registered email address. The code is valid for 15 minutes. Always returns the same response regardless of whether the username exists, to prevent username enumeration.

**Request body**

| Field      | Type   | Required | Description   |
|------------|--------|----------|---------------|
| `username` | string | Yes      | Account username |

**Responses**

| Status | Description            | Body                              |
|--------|------------------------|-----------------------------------|
| `200`  | Request processed      | `{ success: true, message }`      |
| `400`  | Missing username       | `{ success: false, error }`       |
| `500`  | Server error           | `{ success: false, error }`       |

---

#### `POST /api/auth/reset-confirm`

Verify a reset code and replace the account password. **All existing notifications and push subscriptions for the account are deleted** and a new user ID is issued.

**Request body**

| Field         | Type   | Required | Description                 |
|---------------|--------|----------|-----------------------------|
| `code`        | string | Yes      | 6-digit code from the email |
| `newPassword` | string | Yes      | Replacement password        |

**Responses**

| Status | Description                  | Body                                          |
|--------|------------------------------|-----------------------------------------------|
| `200`  | Reset successful             | `{ success: true, user: { userId, username, email } }` |
| `400`  | Missing fields or bad code   | `{ success: false, error }`                   |
| `500`  | Server error                 | `{ success: false, error }`                   |

---

### Notifications

#### `GET /api/notifications`

Retrieve all notifications for the authenticated user, ordered newest first.

**Query parameters**

| Parameter | Type   | Required | Description   |
|-----------|--------|----------|---------------|
| `userId`  | string | Yes      | User UUID     |

**Responses**

| Status | Description              | Body                    |
|--------|--------------------------|-------------------------|
| `200`  | Success                  | Array of notification objects |
| `401`  | Missing or invalid userId | `{ success: false, error }` |
| `500`  | Server error             | `{ success: false, error }` |

**Notification object**

```json
{
  "id": "1712870400000",
  "title": "Example",
  "body": "Notification body text",
  "timestamp": "2024-04-11T20:00:00.000Z",
  "actionTaken": "reply",
  "actionResponse": "OK"
}
```

> `actionTaken` and `actionResponse` are only present if an action was recorded via `POST /api/notifications/:id/actions`.

---

#### `POST /api/notifications`

Store a new notification and immediately fan out web-push messages to all push subscriptions belonging to the user.

**Request body**

| Field       | Type   | Required | Description                                   |
|-------------|--------|----------|-----------------------------------------------|
| `userId`    | string | Yes      | User UUID                                     |
| `title`     | string | No       | Notification title                            |
| `body`      | string | No       | Notification body text                        |
| `timestamp` | string | No       | ISO 8601 timestamp (auto-set if omitted)      |
| `...`       | any    | No       | Any additional fields are stored in `data`    |

**Responses**

| Status | Description          | Body                            |
|--------|----------------------|---------------------------------|
| `200`  | Success              | `{ success: true, id: "<id>" }` |
| `401`  | Missing/invalid userId | `{ success: false, error }`   |
| `500`  | Server error         | `{ success: false, error }`     |

---

#### `DELETE /api/notifications/:id`

Delete a notification. Only the owning user can delete their own notifications.

**Path parameters**

| Parameter | Description         |
|-----------|---------------------|
| `id`      | Notification ID     |

**Query parameters**

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `userId`  | string | Yes      | User UUID   |

**Responses**

| Status | Description             | Body                            |
|--------|-------------------------|---------------------------------|
| `200`  | Deleted successfully    | `{ success: true }`             |
| `401`  | Missing/invalid userId  | `{ success: false, error }`     |
| `500`  | Server error            | `{ success: false, error }`     |

---

#### `POST /api/notifications/:id/actions`

Record the action taken on a notification (e.g. a reply or button tap). Updates the `actionTaken` and `actionResponse` fields on the stored notification.

**Path parameters**

| Parameter | Description     |
|-----------|-----------------|
| `id`      | Notification ID |

**Request body**

| Field      | Type   | Required | Description                        |
|------------|--------|----------|------------------------------------|
| `userId`   | string | Yes      | User UUID                          |
| `action`   | string | Yes      | Action identifier (e.g. `"reply"`) |
| `response` | string | No       | User-provided response text        |

**Responses**

| Status | Description               | Body                            |
|--------|---------------------------|---------------------------------|
| `200`  | Action recorded           | `{ success: true }`             |
| `401`  | Missing/invalid userId    | `{ success: false, error }`     |
| `404`  | Notification not found    | `{ success: false, error }`     |

---

#### `POST /api/device-tokens`

Register or update an Android FCM device token for push delivery to the phone app.
Uses `INSERT OR REPLACE`, so re-registering the same userId updates the stored token.

**Request body**

| Field    | Type   | Required | Description              |
|----------|--------|----------|--------------------------|
| `userId` | string | Yes      | User UUID                |
| `token`  | string | Yes      | FCM registration token   |

**Responses**

| Status | Description             | Body                        |
|--------|-------------------------|-----------------------------|
| `200`  | Token stored            | `{ success: true }`         |
| `401`  | Missing/invalid userId  | `{ success: false, error }` |
| `500`  | Server error            | `{ success: false, error }` |

> When a notification is dismissed or an action is taken via the web UI, the backend should
> send a FCM **data message** (not a notification message) to the stored token:
>
> Dismiss:
> ```json
> { "type": "dismiss", "notificationId": "<serverId>" }
> ```
> Action:
> ```json
> { "type": "action", "notificationId": "<serverId>", "actionTaken": "<title>", "actionResponse": "<text>" }
> ```
> `actionResponse` is optional. The `actionTaken` value must exactly match the action title
> string stored in the notification's `actions` array (or a recognised alias — see app code).

---

### Push Subscriptions

#### `GET /api/vapid-public-key`

Retrieve the VAPID public key needed to subscribe to web push in the browser.

**No authentication required.**

**Responses**

| Status | Body                          |
|--------|-------------------------------|
| `200`  | `{ publicKey: "<base64url>" }` |

---

#### `POST /api/subscribe`

Register a browser push subscription for the authenticated user. Uses `INSERT OR REPLACE`, so re-registering the same endpoint updates it.

**Request body**

The body must be a valid [PushSubscription](https://developer.mozilla.org/en-US/docs/Web/API/PushSubscription) object with an additional `userId` field:

| Field      | Type   | Required | Description                  |
|------------|--------|----------|------------------------------|
| `userId`   | string | Yes      | User UUID                    |
| `endpoint` | string | Yes      | Push service URL             |
| `keys`     | object | Yes      | `{ p256dh, auth }` key pair  |

**Responses**

| Status | Description             | Body                        |
|--------|-------------------------|-----------------------------|
| `200`  | Subscription stored     | `{ success: true }`         |
| `401`  | Missing/invalid userId  | `{ success: false, error }` |
| `500`  | Server error            | `{ success: false, error }` |

---

#### `POST /api/send-push`

Create and immediately push a notification. Identical to `POST /api/notifications` but intended for testing from the browser UI.

**Request body**

| Field    | Type   | Required | Description       |
|----------|--------|----------|-------------------|
| `userId` | string | Yes      | User UUID         |
| `title`  | string | Yes      | Notification title |
| `body`   | string | Yes      | Notification body  |

**Responses**

| Status | Description           | Body                            |
|--------|-----------------------|---------------------------------|
| `200`  | Success               | `{ success: true, id: "<id>" }` |
| `401`  | Missing/invalid userId | `{ success: false, error }`    |
| `500`  | Server error          | `{ success: false, error }`     |

---

### Users

#### `GET /api/users`

List all users. **Password hashes and internal user IDs are excluded from the response.**

**No authentication required.**

**Responses**

| Status | Body                                                  |
|--------|-------------------------------------------------------|
| `200`  | Array of `{ guid, username, email, created_at, last_active }` |

---

#### `POST /api/users`

Create a new user directly (alternative to `POST /api/auth`). Returns `409` if the username is already taken.

**Request body**

| Field      | Type   | Required | Description         |
|------------|--------|----------|---------------------|
| `username` | string | Yes      | Unique username     |
| `password` | string | Yes      | Plain-text password |
| `email`    | string | No       | Email address       |

**Responses**

| Status | Description           | Body                                        |
|--------|-----------------------|---------------------------------------------|
| `201`  | User created          | `{ success: true, user: { userId, username, email } }` |
| `400`  | Missing fields        | `{ success: false, error }`                 |
| `409`  | Username already taken | `{ success: false, error }`                |
| `500`  | Server error          | `{ success: false, error }`                 |

---

#### `GET /api/users/:userId`

Get a single user by their UUID. Updates `last_active` on each call. **Password hash is excluded.**

**Responses**

| Status | Description      | Body                                                    |
|--------|------------------|---------------------------------------------------------|
| `200`  | Success          | `{ user_id, username, email, created_at, last_active }` |
| `404`  | User not found   | `{ success: false, error }`                             |
| `500`  | Server error     | `{ success: false, error }`                             |

---

#### `GET /api/users/by-username/:username`

Look up a user by username.

**Responses**

| Status | Description     | Body                                                    |
|--------|-----------------|---------------------------------------------------------|
| `200`  | Success         | `{ user_id, username, email, created_at, last_active }` |
| `404`  | User not found  | `{ success: false, error }`                             |
| `500`  | Server error    | `{ success: false, error }`                             |

---

#### `PATCH /api/users/:userId/email`

Update the email address for a user.

**Request body**

| Field   | Type   | Required | Description                              |
|---------|--------|----------|------------------------------------------|
| `email` | string | No       | New email address. Omit or set to `null` to clear. |

**Responses**

| Status | Description      | Body                        |
|--------|------------------|-----------------------------|
| `200`  | Updated          | `{ success: true }`         |
| `404`  | User not found   | `{ success: false, error }` |
| `500`  | Server error     | `{ success: false, error }` |

---

#### `GET /api/users/:userId/notifications`

Get all notifications for a specific user. Does **not** require the `requireUserId` middleware — the path parameter is used directly.

**Responses**

| Status | Description    | Body                          |
|--------|----------------|-------------------------------|
| `200`  | Success        | Array of notification objects |
| `500`  | Server error   | `{ success: false, error }`   |

---

## Error Format

All error responses share a common shape:

```json
{
  "success": false,
  "error": "Human-readable description"
}
```

## Notes

- Users inactive for **30 days** are automatically pruned along with all their notifications and push subscriptions.
- Expired push subscriptions (HTTP 410 from the push service) are removed automatically when a push delivery fails.
- VAPID keys are static. If they are rotated, all stored push subscriptions must be cleared.
