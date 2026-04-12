# QR Code Specification

## Purpose

The QR code allows a smartphone app to be quickly configured to send notifications to a specific user on this server, without manually entering a server URL or user ID.

## Accessing the QR Code

The QR code is available in the web interface once signed in. Click the **QR Code** button in the user bar to open a modal displaying the code.

## Payload Format

The QR code encodes a UTF-8 JSON string with the following fields:

| Field       | Type   | Description                                      |
|-------------|--------|--------------------------------------------------|
| `serverUrl` | string | The origin of the server (e.g. `http://192.168.1.10:3000`) |
| `userId`    | string | The authenticated user's UUID                    |

### Example

```json
{"serverUrl":"http://192.168.1.10:3000","userId":"a1b2c3d4-e5f6-7890-abcd-ef1234567890"}
```

## Using the QR Code

Once scanned, the client app should:

1. Extract `serverUrl` and `userId` from the JSON payload.
2. Send notifications via `POST {serverUrl}/api/notifications` with `userId` included in the request body.

### Minimal notification request

```http
POST /api/notifications HTTP/1.1
Host: 192.168.1.10:3000
Content-Type: application/json

{
  "userId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "title": "Notification title",
  "body": "Notification body text"
}
```

See [API_DOCS.md](API_DOCS.md) for the full notification API reference.

## Security Considerations

- The `userId` acts as a bearer token. Anyone who scans the QR code can send notifications as that user.
- Only display or share the QR code with trusted devices.
- The QR code does **not** contain a password or any credential that grants access to the web interface.
