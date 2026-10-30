# Device pairing

The web UI shows a QR code containing a short-lived pairing token. The phone scans it, shows the user
which controller and account it is about to join, and claims the token. It receives a device id and a
256-bit device secret, which authenticate its WebSocket from then on.

## Flow

```
Browser                      Controller                         Phone
  | POST /api/pairings  -->  | ISSUED token (3 min), hash stored |
  | <-- {id, qrPayload}      |                                   |
  |   shows QR               |                                   |
  |                          | <-- POST /device-api/.../preview  | scans QR
  |                          | --> {accountEmail (masked), exp}  | shows confirm screen
  |                          | <-- POST /device-api/.../claim    | user confirms
  |                          |   T_DEVICE (ACTIVE), token CLAIMED |
  |                          | --> {deviceId, deviceSecret}      | stores secret (Keystore)
  | <-- ws pairing.completed |                                   | starts ConnectionService
  |  navigates to device     |                                   |
```

QR payload: `dcontrol://pair?v=1&u=<url-encoded dcontrol.public-base-url>&t=<token>`.

The claim is written as the user who issued the token (`id_usr_created` of the token row), so the device's
header and audit trail show who paired it.

## State machines

`PAIRING` (`T_PAIRING_TOKEN`):

```
(new) --ISSUE--> ISSUED --CLAIM--> CLAIMED
                 ISSUED --CANCEL--> CANCELLED
```

Expiry is not a state. `PairingCleanupJob` physically deletes every token past `dtt_expires` once a
minute, whatever its state. The device row is the durable record.

`DEVICE` (`T_DEVICE`, audited):

```
(new) --PAIR--> ACTIVE --REVOKE--> REVOKED --REINSTATE--> ACTIVE
```

`DELETE /api/devices/{id}` (unpair) moves the device to REVOKED, sets `is_active = false` and closes its socket
with `4003` after commit. `POST /api/devices/{id}/restore` reinstates it; the phone can reconnect with its
existing secret as long as it hasn't wiped it.

## Endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/pairings` | user | 201 `{id, state, expiresAt, deviceId: null, qrPayload}`; 10/min per user |
| GET | `/api/pairings/{id}` | user | Polling fallback; `qrPayload` is null (the raw token is never stored) |
| DELETE | `/api/pairings/{id}` | user | Cancel; 409 `INVALID_STATE_TRANSITION` if already claimed |
| POST | `/api/device-api/pairings/preview` | token | `{accountEmail, expiresAt}`; 410 `PAIRING_TOKEN_INVALID` |
| POST | `/api/device-api/pairings/claim` | token | 201 `{deviceId, deviceSecret, deviceName}`; 410 `PAIRING_TOKEN_INVALID` |
| GET | `/api/devices[?deleted=true]` | user | Devices with live status |
| GET / PATCH / DELETE | `/api/devices/{id}` | user | PATCH `{name}` renames |
| POST | `/api/devices/{id}/restore` | user | |

Device-api endpoints are limited to 30 requests per minute per IP.

## Security notes

- Tokens are 32 random bytes; only SHA-256 hashes are stored. A token can be claimed once; concurrent claims
  are serialized by optimistic locking.
- The phone always shows a confirmation screen with the controller host and the masked account email. Without
  it, a malicious QR code could silently hand the phone to someone else's account.
- Release builds of the app refuse cleartext URLs; only debug builds accept `http://`.

## Android permissions

| Permission | Kind | How the user grants it |
|---|---|---|
| `CAMERA` | runtime | Asked on the scan screen after a rationale. If denied, the scan screen explains how to enable it in system settings. |
| `INTERNET` | normal | At install |
| `ACCESS_LOCAL_NETWORK` (Android 17+) | runtime | Asked after scanning a code for a controller on a private address (LAN, emulator `10.0.2.2`, `.local`). Without it Android silently drops the connection; pairing then explains how to allow it. A public `https://` controller doesn't need it. |

Debug builds also declare a `dcontrol://pair` deep link so emulators can pair without a camera
(`adb shell 'am start -d "<qrPayload>"'`). The confirmation screen still appears.

## Frontend

`/devices/pair` creates a token on open, shows the QR code with a countdown, and offers "Generate new code"
once it expires. It navigates to the new device when `pairing.completed` arrives for its pairing id, and
polls `GET /api/pairings/{id}` every 3 s while the realtime socket is down.
