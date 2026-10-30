# dcontrol WebSocket protocol — v1

This document is the contract between the controller, the web UI and the Android client.
Anything not described here is rejected by the controller.

## Transport

| Channel | URL | Who connects | Authentication |
|---|---|---|---|
| Device | `wss://<host>/ws/device` | Android client (outbound, works behind NAT) | `Authorization: Device <deviceId>.<deviceSecret>` handshake header |
| Web | `wss://<host>/ws/web?ticket=<ticket>` | Browser | One-time ticket from `POST /api/ws/tickets` (valid 30 s) |

- Text frames only, UTF-8 JSON, **max 64 KiB** per message. Larger frames close the socket with `1009`.
- Binary data (files, screen frames) never travels over these channels.
- A failed handshake is answered with HTTP `401` and no body. The device client treats a `401`
  handshake response as "credentials no longer valid".

## Envelope

```json
{
  "v": 1,
  "type": "device.hello",
  "requestId": "01929a3e-6f7c-7d1e-9a0b-3c4d5e6f7a8b",
  "payload": { },
  "error": { "code": "TIMEOUT", "message": "Device did not answer in time" }
}
```

| Field | Required | Notes |
|---|---|---|
| `v` | yes | Protocol version. Must be `1`. |
| `type` | yes | `<domain>.<action>`; replies are `<domain>.<action>.result`. |
| `requestId` | for commands and their results | UUID string. A result carries the `requestId` of the command it answers. |
| `payload` | per type | JSON object. Omitted when a type has no payload. |
| `error` | only on failed results and `error` messages | `{ "code": string, "message": string }`. |

Unknown fields are ignored. Unknown `type` values, a wrong `v`, malformed JSON or an invalid payload
produce an `error` message with code `VALIDATION`; the third such violation on one connection closes it
with `4400`.

### Error codes

The same codes appear in REST problem details (`code` property).

| Code | Meaning |
|---|---|
| `VALIDATION` | Malformed message or payload |
| `NOT_FOUND` | Device does not exist or does not belong to the caller |
| `DEVICE_OFFLINE` | Target device has no open connection |
| `DEVICE_DISCONNECTED` | Device disconnected before answering |
| `TIMEOUT` | Device did not answer within 15 s |
| `UNSUPPORTED` | Device does not implement the command, or the target does not support it (e.g. reply on a notification without a reply action) |
| `PERMISSION_REQUIRED` | The phone lacks an access the command needs (All files access, Notification access, from Phase 3 a runtime permission, from Phase 4 location or the Accessibility service, and from Phase 5 the camera, named in `details.permission`) |
| `FORBIDDEN` | The phone refuses the target (path outside storage roots, notification not clearable) |
| `CONFLICT` | Target already exists, or a transfer chunk does not start at the received offset |
| `PAYLOAD_TOO_LARGE` | Size limit exceeded (transfer size, account spool quota, icon size) |
| `RATE_LIMITED` | Too many messages, or too many `sms.send` / `call.place` (Phase 3) or `screen.start` / `find.ring` / `find.lock` (Phase 4) or `camera.start` / `camera.capture` (Phase 5) for one phone |
| `INTERNAL` | Unexpected server error |

### Close codes

| Code | Meaning | Client reaction |
|---|---|---|
| `1000` | Normal close | Device: reconnect with backoff |
| `1001` | Server shutting down | Reconnect with backoff |
| `1009` | Message too big | Reconnect with backoff |
| `4001` | Unauthorized | Device: wipe credentials, show "unpaired". Web: refresh session and get a new ticket |
| `4003` | Device revoked (unpaired from the web UI) | Device: wipe credentials, show "unpaired", stop |
| `4009` | Superseded by a newer connection for the same device or ticket | Device: reconnect only after the maximum backoff (60 s) |
| `4400` | Protocol violation (3 invalid messages, or no `device.hello` within 10 s) | Reconnect with backoff |
| `4429` | Rate limited | Reconnect with backoff |

### Rate limits

- Device channel: 60 messages per 10 s.
- Web channel: 20 messages per 10 s.

Exceeding a limit sends one `error` (`RATE_LIMITED`) and closes with `4429`.

## Device channel

### Connection lifecycle

1. The client opens `/ws/device` with the `Authorization` header. OkHttp `pingInterval` = 20 s keeps NAT mappings alive.
2. The client sends `device.hello` immediately. If it doesn't arrive within 10 s, the server closes with `4400`.
3. The server replies `hello.ack`. The client resets its reconnect backoff.
4. The client sends `device.heartbeat` every `heartbeatIntervalSec` seconds.
5. The server considers the device offline when it has received no frame for 75 s, and closes the socket.
6. On any close or failure, the client reconnects with exponential backoff: delay = random(0, min(60 s, 1 s × 2^attempt)) ("full jitter"). It also reconnects immediately when the network becomes available.

### Phone → controller

#### `device.hello`

```json
{
  "v": 1, "type": "device.hello",
  "payload": {
    "appVersion": "1.0.0",
    "manufacturer": "Google",
    "model": "Pixel 9",
    "androidVersion": "17",
    "sdkInt": 37,
    "battery": { "level": 87, "charging": false }
  }
}
```

| Field | Rules |
|---|---|
| `appVersion` | 1–32 chars |
| `manufacturer`, `model` | 1–64 chars |
| `androidVersion` | 1–16 chars |
| `sdkInt` | 26–100 |
| `battery.level` | 0–100 |
| `battery.charging` | boolean |

#### `device.heartbeat`

```json
{ "v": 1, "type": "device.heartbeat", "payload": { "battery": { "level": 86, "charging": true } } }
```

No reply.

#### `<command>.result`

Answer to a command, with the command's `requestId`. Either `payload` or `error` is present.

```json
{ "v": 1, "type": "device.ping.result", "requestId": "…", "payload": { "deviceTime": "2026-10-29T10:15:30.123Z" } }
{ "v": 1, "type": "device.ping.result", "requestId": "…", "error": { "code": "UNSUPPORTED", "message": "…" } }
```

A device that receives a command type it doesn't know replies `<type>.result` with error `UNSUPPORTED`.

### Controller → phone

#### `hello.ack`

```json
{ "v": 1, "type": "hello.ack", "payload": { "serverTime": "2026-10-29T10:15:30Z", "heartbeatIntervalSec": 30, "deviceName": "Pixel 9" } }
```

#### `device.ping` (command)

```json
{ "v": 1, "type": "device.ping", "requestId": "…" }
```

Reply: `device.ping.result` with `{ "deviceTime": ISO-8601 instant }`.

#### `error`

```json
{ "v": 1, "type": "error", "requestId": "…optional…", "error": { "code": "VALIDATION", "message": "Unknown type" } }
```

## Web channel

### Web → controller

#### `device.ping` (command)

```json
{ "v": 1, "type": "device.ping", "requestId": "<browser uuid>", "payload": { "deviceId": "…" } }
```

### Controller → web

| Type | Payload | When |
|---|---|---|
| `devices.snapshot` | `{ "devices": [DeviceStatus…] }` | Right after the socket opens |
| `device.status` | `DeviceStatus` | Device connects or disconnects, battery changes, device renamed, revoked or restored |
| `pairing.completed` | `{ "pairingId": uuid, "deviceId": uuid }` | A phone claimed a pairing token of this account |
| `device.ping.result` | `{ "rttMs": number, "deviceTime": ISO-8601 }` or `error` | Answer to `device.ping` with the browser's `requestId` |
| `error` | — | Validation or relay errors |

`DeviceStatus`:

```json
{
  "deviceId": "…",
  "name": "Pixel 9",
  "online": true,
  "battery": { "level": 86, "charging": true },
  "lastSeen": "2026-10-29T10:15:30Z"
}
```

`battery` and `lastSeen` are `null` until the device has connected once.

## Relay rules

- The controller checks that `payload.deviceId` belongs to the account in the caller's `aid` claim.
  Devices of other accounts answer `NOT_FOUND`, exactly like nonexistent ones.
- The controller generates its own `requestId` for the phone and maps the result back to the browser's
  `requestId`. Browser ids never reach the phone.
- Pending commands fail with `TIMEOUT` after 15 s, or `DEVICE_DISCONNECTED` when the device socket closes.
- Results with an unknown or already completed `requestId` are dropped.

## Pairing REST endpoints used by the phone

QR code content:

```
dcontrol://pair?v=1&u=<url-encoded controller base URL>&t=<pairing token>
```

The token is valid for 3 minutes and can be claimed once.

### `POST /api/device-api/pairings/preview`

Request `{ "token": "…" }` → `200`:

```json
{ "accountEmail": "r***@example.com", "expiresAt": "2026-10-29T10:18:30Z" }
```

### `POST /api/device-api/pairings/claim`

Request:

```json
{
  "token": "…",
  "device": {
    "name": "Pixel 9",
    "manufacturer": "Google",
    "model": "Pixel 9",
    "androidVersion": "17",
    "sdkInt": 37,
    "appVersion": "1.0.0"
  }
}
```

→ `201`:

```json
{ "deviceId": "…", "deviceSecret": "<43-char base64url>", "deviceName": "Pixel 9" }
```

Field rules match `device.hello`. `name` is 1–64 chars.

Both endpoints answer `410` with problem `code` `PAIRING_TOKEN_INVALID` for unknown, expired, cancelled
or already claimed tokens. They are rate limited per client IP.

### Problem details

All REST errors use `application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Gone",
  "status": 410,
  "detail": "Pairing token is invalid or expired",
  "instance": "/api/device-api/pairings/claim",
  "code": "PAIRING_TOKEN_INVALID",
  "errors": [ { "field": "device.name", "message": "must not be blank" } ],
  "traceId": "…"
}
```

## Phase 2 additions (still v1; all additive)

Common value rules:

| Value | Rules |
|---|---|
| `path` | Absolute POSIX path on the phone, 1–4096 chars, no NUL. The phone canonicalizes it and answers `FORBIDDEN` when it is outside a storage root. |
| `name`, `newName`, `fileName` | 1–255 chars, no `/`, no NUL, not `.` or `..` |
| timestamps | ISO-8601 instants (`2026-10-29T10:15:30Z`) |
| byte counts | JSON integers ≥ 0 |
| `key` (notification) | 1–512 chars, the `StatusBarNotification.key` |
| `iconHash` | 64 lowercase hex chars (SHA-256 of the PNG bytes) or `null` |

Phones may answer commands with these error codes only: `VALIDATION`, `NOT_FOUND`, `CONFLICT`, `FORBIDDEN`,
`PERMISSION_REQUIRED`, `UNSUPPORTED`, `PAYLOAD_TOO_LARGE`, `INTERNAL`. Anything else reaches the browser as
`INTERNAL`. Error messages are cut to 200 chars.

### Relayed commands (web → controller → phone)

The browser sends each with `payload.deviceId`; the controller strips nothing else but forwards the payload
**without** `deviceId` to the phone. Results go back as `<type>.result`. Results larger than 64 KiB cannot
exist (frame limit); list results are paged by the phone to stay under ~48 KiB.

| Type | Payload (to phone) | Result payload |
|---|---|---|
| `device.info` | `{}` | `DeviceInfo` |
| `files.roots` | `{}` | `{ "volumes": [Volume] }` (≤ 16) |
| `files.list` | `{ "path", "offset": int ≥ 0 }` | `{ "path", "entries": [FileEntry] (≤ 1000), "total": int, "nextOffset": int \| null }` |
| `files.mkdir` | `{ "path" (parent dir), "name" }` | `{ "entry": FileEntry }` |
| `files.rename` | `{ "path", "newName" }` | `{ "entry": FileEntry }` |
| `files.delete` | `{ "paths": [path] (1–100) }` | `{ "deleted": int, "failures": [{ "path", "code", "message" }] (≤ 100) }` |
| `notifications.list` | `{ "offset": int ≥ 0 }` | `{ "access": "GRANTED" \| "MISSING", "items": [Notification] (≤ 200), "nextOffset": int \| null }` |
| `notification.dismiss` | `{ "key" }` | `{}` |
| `notification.reply` | `{ "key", "text" (1–1000 chars) }` | `{}` |
| `clipboard.set` | `{ "text" (1–16000 chars) }` | `{}` |

`files.list` sorts directories first, then by name (case-insensitive). `files.delete` deletes directories
recursively. `files.*` answer `PERMISSION_REQUIRED` when All files access (API 30+) or storage permission
(API 26–29) is missing; `notification.*` answer `PERMISSION_REQUIRED` without Notification access;
`notifications.list` answers normally with `"access": "MISSING"` and no items.

`Volume`:

```json
{ "id": "primary", "label": "Internal storage", "path": "/storage/emulated/0",
  "totalBytes": 128000000000, "freeBytes": 51000000000, "removable": false, "primary": true }
```

`id` 1–64 chars, `label` 1–100 chars.

`FileEntry`:

```json
{ "name": "IMG_0001.jpg", "path": "/storage/emulated/0/DCIM/IMG_0001.jpg", "dir": false,
  "size": 2048576, "modified": "2026-10-29T10:15:30Z", "mime": "image/jpeg", "hidden": false }
```

`size` is 0 for directories; `mime` is `null` for directories and unknown types (≤ 127 chars).

`Notification`:

```json
{ "key": "0|com.whatsapp|1|…", "packageName": "com.whatsapp", "appLabel": "WhatsApp",
  "iconHash": "9f86d0…", "title": "Alice", "text": "See you at 6?", "subText": null,
  "postedAt": "2026-10-29T10:15:30Z", "ongoing": false, "clearable": true, "canReply": true }
```

`packageName` 1–255, `appLabel` 1–100, `title` ≤ 200 or `null`, `text` ≤ 2000 or `null`, `subText` ≤ 200 or `null`.
The phone truncates; the controller rejects longer values.

`DeviceInfo`:

```json
{
  "os": { "androidVersion": "17", "sdkInt": 37, "securityPatch": "2026-10-05", "buildId": "BP4A.261005.001",
          "manufacturer": "Google", "model": "Pixel 9", "abis": ["arm64-v8a"] },
  "uptimeMs": 86400000,
  "cpuCores": 8,
  "memory": { "totalBytes": 12000000000, "availableBytes": 5000000000, "lowMemory": false },
  "storage": [Volume],
  "battery": { "level": 86, "charging": true, "health": "GOOD", "plugged": "USB",
               "temperatureC": 31.5, "voltageMv": 4200 },
  "display": { "widthPx": 1080, "heightPx": 2424, "densityDpi": 420, "refreshRateHz": 120.0 },
  "network": { "type": "WIFI", "metered": false, "ipAddresses": ["192.168.1.23", "fe80::1"] },
  "locale": "en-US",
  "timeZone": "Europe/Berlin"
}
```

Enums: `health` ∈ `GOOD, OVERHEAT, DEAD, OVER_VOLTAGE, COLD, FAILURE, UNKNOWN`; `plugged` ∈
`AC, USB, WIRELESS, DOCK, NONE`; `network.type` ∈ `WIFI, CELLULAR, ETHERNET, VPN, OTHER, NONE`.
`securityPatch` may be `null`; `abis` ≤ 8 entries; `ipAddresses` ≤ 16 entries, each ≤ 64 chars.

### Phone → controller events (no `requestId`)

| Type | Payload | Controller forwards to the account's browsers as |
|---|---|---|
| `notification.posted` | `Notification` | `notification.posted` `{ "deviceId", "notification": Notification }` |
| `notification.removed` | `{ "key" }` | `notification.removed` `{ "deviceId", "key" }` |
| `clipboard.changed` | `{ "text" (1–16000) }` | `clipboard.changed` `{ "deviceId", "text", "receivedAt" }` |
| `transfer.progress` | `{ "transferId", "bytes" }` | `transfer.progress` `{ "deviceId", "transferId", "bytes", "size" }` |
| `transfer.finished` | `{ "transferId", "ok": bool, "errorCode": string \| null, "message": string \| null }` | `transfer.status` (see below) |

The phone coalesces repeated `notification.posted` updates for the same key (at most one per key per
250 ms) and sends `transfer.progress` at most once per second per transfer. Events that fail validation
count as protocol violations. A `transfer.*` event about a transfer that does not belong to the sending
device is ignored.

### Controller → phone transfer messages

| Type | Kind | Payload | Phone reply |
|---|---|---|---|
| `transfer.send` | command | `{ "transferId", "path" }` | `.result` `{ "fileName", "size", "modified" }` once the file is found and readable, then uploads (see transfers-v1.md). Errors: `NOT_FOUND`, `FORBIDDEN`, `PERMISSION_REQUIRED`, `VALIDATION` (path is a directory) |
| `transfer.receive` | command | `{ "transferId", "targetDir", "fileName", "size", "sha256" }` | `.result` `{}` when accepted, then downloads. Errors: `NOT_FOUND` (targetDir), `FORBIDDEN`, `PERMISSION_REQUIRED`, `PAYLOAD_TOO_LARGE` (not enough free space) |
| `transfer.cancel` | event | `{ "transferId" }` | none; the phone stops and deletes partial files |

The controller re-sends `transfer.receive` for every READY `TO_DEVICE` transfer of a device after its
`hello.ack`, and `transfer.send` for every RECEIVING `FROM_DEVICE` transfer. Phones must treat both as
idempotent by `transferId` (resume instead of restarting).

### Controller → web additions

| Type | Payload |
|---|---|
| `transfer.status` | `Transfer` (same JSON as `GET /api/transfers/{id}`), sent on every state change |
| `transfer.progress` | `{ "deviceId", "transferId", "bytes", "size" }`, at most once per second per transfer |
| `notification.posted`, `notification.removed`, `clipboard.changed` | see the table above |

## Phase 3 additions (still v1; all additive)

Contacts, call log and SMS are read live from the phone and **never stored** on the controller. The
controller validates, caps and forwards. It never logs payloads (numbers, names, message text).

### Common value rules

| Value | Rules |
|---|---|
| `id`, `threadId` | JSON integer ≥ 0 (the provider's `_ID`) |
| `lookupKey` | 1–512 chars (`ContactsContract.Contacts.LOOKUP_KEY`) |
| `cursor`, `nextCursor` | Opaque paging token. The phone makes it and the web echoes it back unchanged: 1–256 printable ASCII chars, or `null` / absent on the first page and after the last |
| phone number as stored (`PhoneNumber`) | 1–64 chars, no control characters. SMS sender addresses can be alphanumeric (`VM-BANK`) |
| dialable number (`DialNumber`) | Only in `call.place` and `sms.send`. Allowed characters: `+`, `0-9`, space, `(`, `)`, `-`, `.`. After removing space, `(`, `)`, `-` and `.`, it must match `^\+?[0-9]{2,20}$`. `*` and `#` are rejected, so the web can never run MMI/USSD codes. The controller forwards the stripped form |
| `simId` | `SubscriptionInfo.subscriptionId` (integer), or `null` for the phone's default |
| `clientRef` | UUID chosen by the browser |
| timestamps | ISO-8601 instants with millisecond precision |

Lists are ordered newest first (calls, threads, messages) or by display name (contacts). Each page is
filled up to ~48 KiB of encoded items.

### `PERMISSION_REQUIRED` details

An error may carry `details`. Phones send it only with `PERMISSION_REQUIRED`:

```json
{ "code": "PERMISSION_REQUIRED", "message": "SMS permission missing", "details": { "permission": "SMS" } }
```

- `permission` ∈ `CONTACTS, CALL_LOG, SMS, PHONE`.
- The controller drops any other value, and `details` on any other code.
- The browser sees the same `details` on the `<type>.result` error. REST problem details carry it as the
  `permission` property.

`RATE_LIMITED` can also be the error of a relayed `sms.send` or `call.place`, when a per-device limit is
exceeded (see [sms.md](../features/sms.md)). Unlike the socket rate limit, it doesn't close the connection.

### Relayed commands (web → controller → phone)

| Type | Payload (to phone) | Result payload | Phone permission |
|---|---|---|---|
| `contacts.list` | `{ "query": string ≤ 100 \| null, "cursor" }` | `{ "items": [ContactSummary] (≤ 500), "nextCursor" }` | CONTACTS |
| `contacts.get` | `{ "id", "lookupKey" }` | `Contact` | CONTACTS |
| `contacts.accounts` | `{}` | `{ "accounts": [Account] (≤ 32) }` | CONTACTS |
| `contacts.create` | `{ "account": AccountRef \| null, "fields": ContactFields }` | `Contact` | CONTACTS |
| `contacts.update` | `{ "id", "lookupKey", "version", "fields": ContactFields }` | `Contact` | CONTACTS |
| `contacts.delete` | `{ "items": [{ "id", "lookupKey" }] (1–100) }` | `{ "deleted": int, "failures": [{ "id", "code", "message" }] (≤ 100) }` | CONTACTS |
| `calls.list` | `{ "type": CallType \| null, "cursor" }` | `{ "items": [CallEntry] (≤ 500), "nextCursor" }` | CALL_LOG |
| `calls.delete` | `{ "ids": [id] (1–100) }` | `{ "deleted": int }` | CALL_LOG |
| `call.place` | `{ "number": DialNumber, "simId" }` | `{}` once handed to Telecom | PHONE |
| `telephony.sims` | `{}` | `Sims` | none (the list is empty without PHONE) |
| `sms.threads` | `{ "cursor" }` | `{ "items": [Thread] (≤ 200), "nextCursor" }` | SMS |
| `sms.messages` | `{ "threadId", "cursor" }` | `{ "threadId", "items": [Message] (≤ 500), "nextCursor" }` | SMS |
| `sms.send` | `{ "to": DialNumber, "text" (1–1600 chars), "simId", "clientRef" }` | `{ "parts": int 1–20 }` once queued | SMS |

**Contacts**
- `contacts.get`, `contacts.update` and `contacts.delete` resolve the contact by `lookupKey` first, because
  ids change when contacts are re-aggregated. They answer `NOT_FOUND` when the contact is gone.
- `contacts.update` answers:
  - `CONFLICT` when `version` no longer matches;
  - `FORBIDDEN` when the contact is `readOnly`.
- `contacts.update` rewrites only the editable kinds of data (structured name, phones, emails, organization,
  note, starred). A contact can merge several raw contacts (one per account): the phone removes those kinds
  from every writable one and writes the new values to the first writable one, skipping numbers and emails a
  read-only raw contact already shows. Other data (addresses, events, photo, groups…) is kept.
- `contacts.delete` deletes the aggregate contact. Sync adapters propagate the deletion to the account.

**Calls and SMS**
- `call.place` and `sms.send` answer `UNSUPPORTED` on a phone without telephony.
- `sms.*` answer `UNSUPPORTED` when the phone has no SMS provider.

`Account` / `AccountRef`:

```json
{ "type": "com.google", "name": "alice@example.com", "label": "Google" }
```

- `type` and `name` are 1–255 chars, or both `null` for contacts stored only on the phone.
- `label` is 1–100 chars.
- `AccountRef` is `{ "type", "name" }`.

`ContactSummary`:

```json
{ "id": 42, "lookupKey": "0r42-…", "name": "Alice Example", "starred": false, "photo": true,
  "phones": ["+49 151 2345678"], "emails": ["alice@example.com"] }
```

`name` is 0–200 chars; `phones` holds ≤ 3 `PhoneNumber`s; `emails` holds ≤ 1 address of ≤ 254 chars.

`Contact`:

```json
{
  "id": 42, "lookupKey": "0r42-…", "version": "17:3,18:1",
  "account": { "type": "com.google", "name": "alice@example.com", "label": "Google" },
  "readOnly": false, "starred": false, "photo": true,
  "name": { "display": "Alice Example", "given": "Alice", "middle": null, "family": "Example", "prefix": null, "suffix": null },
  "phones": [{ "number": "+49 151 2345678", "type": "MOBILE", "label": null }],
  "emails": [{ "address": "alice@example.com", "type": "WORK", "label": null }],
  "organization": { "company": "Example GmbH", "title": "CTO" },
  "note": null
}
```

- `version`: 1–256 chars, opaque (the phone builds it from the raw contacts' `VERSION`s).
- `account`: may be `null` (phone-only contact).
- Name: each part ≤ 200 chars or `null`; `display` 0–200 chars.
- `phones` and `emails`: ≤ 20 each.
  - Phone `type` ∈ `MOBILE, HOME, WORK, MAIN, WORK_MOBILE, FAX, PAGER, OTHER, CUSTOM`.
  - Email `type` ∈ `HOME, WORK, MOBILE, OTHER, CUSTOM`.
  - `label`: ≤ 100 chars, used with `CUSTOM`.
- `organization`: `null` or `{company, title}`, each ≤ 200 chars or `null`.
- `note`: ≤ 2000 chars or `null`.

`ContactFields` is `Contact` without `id`, `lookupKey`, `version`, `account`, `readOnly`, `photo` and
`name.display`. At least one name part, phone, email or company must be non-blank.

`CallEntry`:

```json
{ "id": 901, "number": "+491512345678", "presentation": "ALLOWED", "name": "Alice Example",
  "type": "MISSED", "date": "2026-10-29T10:15:30.123Z", "durationSec": 0, "simLabel": "Work", "isNew": true }
```

- `number`: a `PhoneNumber`, or `null` when withheld.
- `presentation` ∈ `ALLOWED, RESTRICTED, UNKNOWN, PAYPHONE`.
- `name`: ≤ 200 chars or `null` (from `CACHED_NAME`).
- `type` (`CallType`) ∈ `INCOMING, OUTGOING, MISSED, VOICEMAIL, REJECTED, BLOCKED, ANSWERED_EXTERNALLY, OTHER`.
  `OTHER` is never a valid filter.
- `durationSec`: ≥ 0.
- `simLabel`: ≤ 100 chars or `null`.

`Sims`:

```json
{ "hasTelephony": true, "access": "GRANTED", "defaultSmsSimId": 1, "defaultCallSimId": null,
  "sims": [{ "id": 1, "label": "Work", "slot": 0, "carrier": "Telekom" }] }
```

- `access` ∈ `GRANTED, MISSING` (READ_PHONE_STATE).
- `sims`: ≤ 8 entries, empty when `MISSING`.
- `label` and `carrier`: ≤ 100 chars; `carrier` may be `null`.
- `slot`: ≥ 0.

`Thread`:

```json
{ "threadId": 7, "participants": [{ "address": "+491512345678", "name": "Alice Example" }],
  "snippet": "See you at 6?", "date": "2026-10-29T10:15:30.123Z", "unreadCount": 1, "messageCount": 54,
  "hasMms": false }
```

- `participants`: 1–10 entries.
  - `address` is a `PhoneNumber`.
  - `name` is ≤ 200 chars or `null`. It is resolved with `PhoneLookup` when CONTACTS is granted.
- `snippet`: ≤ 200 chars or `null`.
- Counts: ≥ 0.

`Message`:

```json
{ "id": 3301, "kind": "SMS", "box": "INBOX", "address": "+491512345678", "body": "See you at 6?",
  "date": "2026-10-29T10:15:30.123Z", "read": true, "status": "NONE", "simLabel": null, "mmsSummary": null }
```

- `id` is unique per `kind` only (SMS and MMS live in separate tables).
- `kind` ∈ `SMS, MMS`.
- `box` ∈ `INBOX, SENT, OUTBOX, FAILED, QUEUED, DRAFT`.
- `address`: a `PhoneNumber` or `null`.
- `body`: ≤ 2000 chars or `null`.
- `status` ∈ `NONE, PENDING, COMPLETE, FAILED`.
- `simLabel`: ≤ 100 chars or `null`.
- `mmsSummary`: ≤ 200 chars or `null`. Set only for MMS: `"MMS"` or `"MMS · <subject>"`; MMS parts aren't read.

### Contact photos (controller → phone, HTTP upload)

The browser loads `GET /api/devices/{deviceId}/contacts/{id}/photo?lookupKey=…` (session auth). The
controller creates a single-use in-memory upload slot and sends the phone a command:

| Type | Kind | Payload | Phone reply |
|---|---|---|---|
| `contacts.photo` | command | `{ "uploadId", "id", "lookupKey" }` | Uploads the photo, then `.result` `{}`. Errors: `NOT_FOUND` (no contact, or no photo), `PERMISSION_REQUIRED` |

**The phone's upload:** `PUT /api/device-api/blobs/{uploadId}`, with `Authorization: Device id.secret`,
`Content-Type: image/jpeg` or `image/png`, and a body of at most 256 KiB. Answers:

| Status | Meaning |
|---|---|
| `204` | Accepted |
| `404` | Unknown, expired or already used slot, or another device's slot |
| `413` | Too large |
| `415` | The bytes are not JPEG or PNG |

Slots expire after 20 s and are never written to disk.

**The browser's answer:** the image, with `Cache-Control: private, max-age=300` and
`X-Content-Type-Options: nosniff`, or a problem:

| Status | Code |
|---|---|
| `404` | `NOT_FOUND` |
| `409` | `DEVICE_OFFLINE` |
| `504` | `TIMEOUT` |
| `409` | `PERMISSION_REQUIRED`, with the `permission` property |

### Phone → controller events (no `requestId`)

Sent only while the phone is connected. The phone watches the providers with `ContentObserver`s
(debounced to 1 s); there is no `RECEIVE_SMS` receiver.

| Type | Payload | Controller forwards to the account's browsers as |
|---|---|---|
| `sms.received` | `{ "threadId", "message": Message }` (a new `INBOX` row) | `sms.received` `{ "deviceId", "threadId", "message" }` |
| `sms.status` | `{ "clientRef", "status": "SENT" \| "DELIVERED" \| "FAILED", "errorCode": string ≤ 64 \| null }` | `sms.status` `{ "deviceId", "clientRef", "status", "errorCode" }` |
| `sms.changed` | `{}` | `sms.changed` `{ "deviceId" }` |
| `calls.changed` | `{}` | `calls.changed` `{ "deviceId" }` |
| `contacts.changed` | `{}` | `contacts.changed` `{ "deviceId" }` |

- The `*.changed` events are hints: the browser refetches what it shows.
- A multipart SMS reports one `SENT` once all parts are sent, or `FAILED` on the first failure. It reports
  one `DELIVERED` once all parts are delivered.

## Phase 4 additions (still v1; all additive)

Phase 4 adds screen mirroring with phone audio, remote control and find my phone.

- **Mirroring and control:** video, audio and input go peer to peer between the browser and the phone over WebRTC,
  or through the TURN relay. The controller only relays signaling and **never sees the media**.
- **Location:** the last fix of each phone is stored. This is the only phone data Phase 4 keeps.

### `PERMISSION_REQUIRED` values

`details.permission` also accepts:
- `LOCATION`: location "Allow all the time" is missing;
- `ACCESSIBILITY`: the dcontrol Remote control service is off.

### ICE servers

`IceServer = { "urls": [string, 1–4], "username": string | null, "credential": string | null }`, the
`RTCIceServer` shape.
- The controller builds the list per socket from `dcontrol.webrtc.ice-servers`, replacing `{host}` with the host
  that socket connected to. The browser and the phone can get different hosts, for example `localhost` and
  `10.0.2.2`.
- TURN entries carry short-lived credentials in coturn's `use-auth-secret` scheme:
  - username: `"<unix expiry>:<user id>"`;
  - credential: `base64(HMAC-SHA1(secret, username))`.
- Never log them.

### Screen session

```
web                         controller                              phone
 | screen.start (command) -->  | checks owner, limit, audits            |
 |                             | creates sessionId -- screen.start --->  | (command)
 | <-- screen.start.result --  | <------------- screen.start.result --   | {audio, control}
 |                             | <------- screen.state AWAITING_CONSENT  | consent screen or notification
 | <-- screen.state ---------  |                                         | user taps Start
 | <-- rtc.signal OFFER -----  | <----------------- rtc.signal OFFER --  | ICE gathered (≤ 4 s)
 | rtc.signal ANSWER ------->  | -------------------- rtc.signal ----->  |
 | <-- screen.state LIVE ----  | <------------------ screen.state LIVE   |
 | screen.stop ------------->  | ------------------- screen.stop ----->  |
```

**Session rules:**
- Signaling is **non-trickle**: each side waits until ICE gathering completes (at most 4 s), then sends one SDP
  that already contains its candidates.
- The phone always makes the offer.
- At most one session per phone. A new `screen.start` replaces the old session: the old browser gets `ENDED`
  with `REPLACED`, and the phone gets `screen.stop` for the old id.
- The session belongs to the browser socket that started it. Only that socket gets its `screen.state` and
  `rtc.signal`, and only it may send `rtc.signal` or `screen.stop` for it.
  - A message naming a session of another socket or another phone is a protocol violation (`VALIDATION`). It
    counts towards the 3 violations that close the socket.
  - A message naming a session that no longer exists is ignored, because it can race with the end of the session.
- **The controller ends the session itself** and tells the phone with `screen.stop` and the browser with
  `screen.state ENDED` when:
  - the browser socket closes (the browser is not told);
  - the phone disconnects (`DEVICE_OFFLINE`; the phone is not told);
  - no `STARTING` or `LIVE` arrives within `dcontrol.webrtc.consent-timeout` (60 s; `TIMEOUT`).

#### `screen.start` (web → controller → phone, command)

| Direction | Payload |
|---|---|
| web → controller | `{ "deviceId", "quality": "LOW" \| "MEDIUM" \| "HIGH", "audio": bool, "control": bool }` |
| controller → phone | `{ "sessionId": uuid, "viewer": string ≤ 100, "quality", "audio", "control", "iceServers": [IceServer, ≤ 8] }` |
| phone → controller result | `{ "audio": bool, "control": bool }`: what the phone can provide. `audio` is false without `RECORD_AUDIO`; `control` is false while the Accessibility service is off |
| controller → web result | `{ "sessionId", "iceServers": [IceServer], "audio", "control" }` |

- The phone answers as soon as it has accepted the request, **before** anyone taps Start.
- The phone sends the result before any `screen.state` for the session. The controller forwards both in that order, so
  the browser learns the `sessionId` first.
- **Opening the consent screen:** directly while the Accessibility service is bound; otherwise through a
  high-importance "Tap to share your screen" notification.
- **Phone errors:** `UNSUPPORTED` (no MediaProjection), `INTERNAL`.
- **Controller errors:** `RATE_LIMITED` (more than `dcontrol.webrtc.start-per-minute`, 6 per phone),
  `NOT_FOUND`, `DEVICE_OFFLINE`, `TIMEOUT`.
- `viewer` is the display name of the user who started the session. The phone shows it in its ongoing
  notification.

**Quality presets:**

| Quality | Long edge | Frame rate | Max bitrate |
|---|---|---|---|
| `LOW` | 720 px | 15 fps | 1 Mbps |
| `MEDIUM` | 1280 px | 30 fps | 2.5 Mbps |
| `HIGH` | 1920 px | 30 fps | 5 Mbps |

The phone never upscales: a screen smaller than the long edge is sent at its own size.

#### `screen.state` (phone → controller → that browser)

| Direction | Payload |
|---|---|
| phone → controller (event) | `{ "sessionId", "state", "reason": string \| null, "control": bool }` |
| controller → web | `{ "deviceId", "sessionId", "state", "reason", "control" }` |

- **`state`:**
  - `AWAITING_CONSENT`: the consent screen or notification is up;
  - `STARTING`: consent was given and the peer connection is being set up;
  - `LIVE`: media is flowing;
  - `ENDED`: the final state.
- **`reason`** is set only with `ENDED`:
  - from the phone: `DENIED`, `STOPPED_ON_PHONE`, `STOPPED`, `ERROR`;
  - from the controller only: `TIMEOUT`, `DEVICE_OFFLINE`, `REPLACED`.
- **`control`** says whether remote input works right now, so it changes if Accessibility is turned on or off
  mid-session.

#### `rtc.signal` (both directions)

| Direction | Payload |
|---|---|
| phone → controller (event) | `{ "sessionId", "kind": "OFFER", "sdp": string ≤ 32768 }` |
| controller → web | `{ "deviceId", "sessionId", "kind": "OFFER", "sdp" }` |
| web → controller (no `requestId`, no result) | `{ "sessionId", "kind": "ANSWER", "sdp" }` |
| controller → phone (no `requestId`) | `{ "sessionId", "kind": "ANSWER", "sdp" }` |

#### `screen.stop`

| Direction | Payload |
|---|---|
| web → controller (no `requestId`, no result) | `{ "sessionId" }` |
| controller → phone (no `requestId`) | `{ "sessionId" }` |

The phone ends the session silently: it doesn't send `ENDED` back after a `screen.stop`.

### Data channel `control` (browser → phone, end to end)

The phone makes the offer, so the channel has to be part of it. **Both sides create it as a pre-negotiated
channel:** label `control`, `negotiated: true`, `id: 0`, ordered and reliable.
- The phone creates it before `createOffer`, only when the `screen.start` result said `control: true`.
- The browser creates the same channel before it applies the offer.

Messages are JSON text of at most 1 KiB. Coordinates are 0..1 fractions of the video frame as currently sent
(the frame follows the phone's rotation).

| Direction | Message |
|---|---|
| browser → phone | `{"t":"down","x":0.5,"y":0.3}`, `{"t":"move",…}`, `{"t":"up",…}`. One pointer; moves are throttled to 60 Hz |
| browser → phone | `{"t":"wheel","x","y","dy"}`: `dy` in CSS pixels, −2000…2000; positive scrolls the content down (a swipe up) |
| browser → phone | `{"t":"key","k":"back" \| "home" \| "recents" \| "notifications" \| "power" \| "lock"}` |
| browser → phone | `{"t":"text","s":"…"}` (1–256 chars, inserted at the cursor of the focused field) |
| browser → phone | `{"t":"edit","k":"backspace" \| "enter"}` |
| phone → browser | `{"t":"size","w":1080,"h":2400,"rotation":0}`: the screen size in pixels, sent on open and on every rotation |
| phone → browser | `{"t":"control","available":true}`: sent on open and whenever Accessibility is turned on or off |

- The phone drops invalid messages: too large, an unknown `t`, or coordinates outside 0..1. After 20 drops it
  ends the session (`ENDED`, reason `STOPPED`).
- Input is only injected while the session is `LIVE` and the Accessibility service is bound.

### Find my phone (web → controller → phone, commands)

| Type | Payload (to phone) | Result | Phone permission |
|---|---|---|---|
| `find.ring` | `{ "action": "START" \| "STOP" }` | `{}` | none |
| `find.lock` | `{ "message": string 1–200, "contact": PhoneNumber \| null }` | `{}` | `ACCESSIBILITY` |

- **`find.ring`:** plays the alarm sound at full alarm volume until `STOP`, the Stop action on the phone, or 5
  minutes, then restores the volume.
  - Limit: `dcontrol.find.ring-per-minute` (10 per phone); audited.
- **`find.lock`:** locks the screen with the Accessibility global action and posts a lock-screen notification
  with the message and contact.
  - `message` may contain line breaks but no other control characters.
  - Limit: `dcontrol.find.lock-per-minute` (5 per phone); audited.

### Locate (controller → phone, command)

`find.locate` has payload `{}`. The browser never sends it; it goes through REST:
`POST /api/devices/{id}/location/refresh`.

The result is a `Location`:

```json
{ "latitude": 23.810331, "longitude": 90.412521, "accuracyM": 12.5, "altitudeM": 8.0,
  "fixedAt": "2026-10-29T10:15:30.123Z", "provider": "GPS", "stale": false }
```

- **Fields:**
  - `latitude` −90…90 and `longitude` −180…180, at most 6 decimals;
  - `accuracyM` 0…1,000,000;
  - `altitudeM` is optional;
  - `provider` ∈ `GPS, NETWORK, FUSED, PASSIVE, OTHER`.
- The phone tries a fresh fix for up to 10 s. If none arrives, it answers with the last known fix and
  `stale: true`.
- **Errors:**
  - `PERMISSION_REQUIRED` (`LOCATION`);
  - `UNSUPPORTED`: location is turned off;
  - `NOT_FOUND`: no fix at all.

### Location REST endpoints (web)

| Endpoint | Result |
|---|---|
| `POST /api/devices/{id}/location/refresh` | `200` with a `StoredLocation` (`Location` plus `deviceId` and `updatedAt`). It is stored, replacing the previous fix. Phone errors map like other relays: `409` with the `permission` property, `409` `DEVICE_OFFLINE`, `504` `TIMEOUT` |
| `GET /api/devices/{id}/location` | `200` with the stored `StoredLocation`, or `404` `NOT_FOUND` when none is stored |
| `DELETE /api/devices/{id}/location` | `204`. The stored fix is physically deleted. It is also deleted when the phone is unpaired |

## Phase 5 additions (still v1; all additive)

Phase 5 adds a consent-gated remote camera: a live viewfinder, photos and short video clips, from the front or the
back camera.

- **Live view:** like screen mirroring, video (and microphone audio when asked for) goes peer to peer between the
  browser and the phone over WebRTC, or through the TURN relay. The controller only relays signaling.
- **Captures:** photos and clips pass through the controller. The phone uploads each capture over HTTP into a
  short-lived spool on the controller's disk, and the browser fetches it from there. Captures are never written to
  the database.
- **Consent:** the phone owner accepts every session on the phone, and the phone shows an ongoing notification with
  Stop for as long as it runs. Nothing is captured before the owner accepts. See `docs/features/camera.md`.

### `PERMISSION_REQUIRED` values

`details.permission` also accepts `CAMERA`: the camera runtime permission is missing.

### Error code additions

- `RATE_LIMITED` also covers `camera.start` and `camera.capture` for one phone.
- `CONFLICT` also covers a capture or a camera switch while the session isn't `LIVE` or another capture is running.

### Camera session

```
web                          controller                               phone
 | camera.start (command) --> | checks owner, limit, audits              |
 |                            | creates sessionId -- camera.start ---->  | (command)
 | <-- camera.start.result -- | <------------- camera.start.result --    | {audio}
 |                            | <------- camera.state AWAITING_CONSENT   | "Tap to share your camera"
 | <-- camera.state --------- |                                          | owner accepts
 | <-- camera.signal OFFER -- | <---------------- camera.signal OFFER -- | ICE gathered (≤ 4 s)
 | camera.signal ANSWER ----> | -------------------- camera.signal ----> |
 | <-- camera.state LIVE ---- | <------------------- camera.state LIVE   |
 | camera.capture ----------> | opens upload slot -- camera.capture ---> | takes photo / records clip
 |                            | <-- PUT /api/device-api/camera/{mediaId} | upload
 | <-- camera.capture.result  | <----------- camera.capture.result ----- |
 | GET .../camera/media/{id}  |                                          |
 | camera.stop -------------> | --------------------- camera.stop -----> |
```

**Session rules:** the same as the screen session (Phase 4), with `camera.*` types:
- Signaling is non-trickle (ICE gathering capped at 4 s). The phone always makes the offer. There is **no data
  channel**: the camera takes no remote input.
- At most one camera session per phone. A new `camera.start` replaces the old one (`ENDED`, `REPLACED`). A camera
  session and a screen session of the same phone are independent.
- The session belongs to the browser socket that started it. Only that socket gets its `camera.state` and
  `camera.signal`, and only it may send `camera.signal`, `camera.stop`, `camera.switch` or `camera.capture` for it.
  - A message naming another socket's or another phone's session is a protocol violation (`VALIDATION`).
  - A message naming a session that no longer exists is ignored (`camera.stop`, `camera.signal`) or answered with
    `NOT_FOUND` (commands).
- The controller ends the session itself, telling the phone with `camera.stop` and the browser with
  `camera.state ENDED`, when the browser socket closes, the phone disconnects (`DEVICE_OFFLINE`), or no `STARTING`
  or `LIVE` arrives within `dcontrol.camera.consent-timeout` (60 s; `TIMEOUT`).

**Values:**
- `Facing`: `FRONT` or `BACK`;
- `state`: `AWAITING_CONSENT`, `STARTING`, `LIVE`, `ENDED`, as for the screen session;
- `reason` (only with `ENDED`): from the phone `DENIED`, `STOPPED_ON_PHONE`, `STOPPED`, `ERROR`; from the
  controller only `TIMEOUT`, `DEVICE_OFFLINE`, `REPLACED`;
- capture `kind`: `PHOTO` or `VIDEO`.

#### `camera.start` (web → controller → phone, command)

| Direction | Payload |
|---|---|
| web → controller | `{ "deviceId", "facing", "audio": bool }` |
| controller → phone | `{ "sessionId": uuid, "viewer": string ≤ 100, "facing", "audio", "iceServers": [IceServer, ≤ 8] }` |
| phone → controller result | `{ "audio": bool }`: false when `RECORD_AUDIO` isn't granted |
| controller → web result | `{ "sessionId", "iceServers": [IceServer], "audio" }` |

- The phone answers as soon as it has accepted the request, before the owner accepts, and before any
  `camera.state` for the session.
- **Asking for consent:** the consent screen opens directly while the dcontrol app is in the foreground; otherwise
  the phone posts a high-importance "Tap to share your camera" notification. The owner always has to accept; it is
  never automatic.
- **Phone errors:**
  - `PERMISSION_REQUIRED` (`CAMERA`): the phone shows nothing;
  - `UNSUPPORTED`: the phone has no camera;
  - `INTERNAL`.
- **Controller errors:** `RATE_LIMITED` (more than `dcontrol.camera.start-per-minute`, 6 per phone), `NOT_FOUND`,
  `DEVICE_OFFLINE`, `TIMEOUT`.

#### `camera.state` (phone → controller → that browser)

| Direction | Payload |
|---|---|
| phone → controller (event) | `{ "sessionId", "state", "reason": string \| null, "facing", "video": bool }` |
| controller → web | `{ "deviceId", "sessionId", "state", "reason", "facing": Facing \| null, "video" }` |

- `facing` is the camera in use. It is null in the `ENDED` the controller sends itself.
  - When the phone has no camera facing the requested way, it opens the other one and says so here.
  - After a successful `camera.switch` the phone sends `LIVE` again with the new `facing`.
- `video` says whether clips can be recorded. It is false when the phone can't run preview, photo and video
  together; then only photos are offered. It is only meaningful from `LIVE` on, because the camera is not opened
  before.

#### `camera.signal` (both directions)

The same shape and rules as `rtc.signal`:

| Direction | Payload |
|---|---|
| phone → controller (event) | `{ "sessionId", "kind": "OFFER", "sdp": string ≤ 32768 }` |
| controller → web | `{ "deviceId", "sessionId", "kind": "OFFER", "sdp" }` |
| web → controller (no `requestId`, no result) | `{ "sessionId", "kind": "ANSWER", "sdp" }` |
| controller → phone (no `requestId`) | `{ "sessionId", "kind": "ANSWER", "sdp" }` |

#### `camera.stop`

| Direction | Payload |
|---|---|
| web → controller (no `requestId`, no result) | `{ "sessionId" }` |
| controller → phone (no `requestId`) | `{ "sessionId" }` |

The phone ends the session silently: it doesn't send `ENDED` back after a `camera.stop`.

#### `camera.switch` (web → controller → phone, command)

| Direction | Payload |
|---|---|
| web → controller | `{ "sessionId", "facing" }` |
| controller → phone | `{ "sessionId", "facing" }` |
| result (both hops) | `{ "facing" }` |

- The stream continues on the same track.
- **Errors:**
  - `UNSUPPORTED`: the phone has no camera facing that way;
  - `CONFLICT`: the session isn't `LIVE`, or a clip is recording;
  - `NOT_FOUND`: the session is gone.

#### `camera.capture` (web → controller → phone, command)

| Direction | Payload |
|---|---|
| web → controller | `{ "sessionId", "kind", "durationMs": int \| null }` |
| controller → phone | `{ "sessionId", "mediaId": uuid, "kind", "durationMs" }` |
| phone → controller result | `{ "width": int, "height": int, "durationMs": int \| null }` |
| controller → web result | `{ "mediaId", "kind", "mime", "bytes", "width", "height", "durationMs", "capturedAt" }` |

- **`durationMs`:** for `VIDEO`, 1000 to `dcontrol.camera.max-video-duration` (60 s); for `PHOTO`, null.
- **The controller checks first:**
  - the session is `LIVE` and no other capture of it is running (`CONFLICT`);
  - the rate limit `dcontrol.camera.capture-per-minute` (20 per phone; `RATE_LIMITED`).

  Then it opens an upload slot named `mediaId` that only this phone may fill, and relays the command.
- **The phone:**
  1. takes a full-resolution JPEG (`PHOTO`), or records an MP4 for `durationMs` (`VIDEO`; with audio when the
     session has audio);
  2. uploads it to the slot;
  3. answers. A result without a valid upload fails with `INTERNAL`.
- **Phone errors:**
  - `CONFLICT`: busy, or not `LIVE`;
  - `UNSUPPORTED`: clips aren't available;
  - `INTERNAL`.

  When the controller refuses its upload, the phone answers with the refusal: `NOT_FOUND` (404),
  `PAYLOAD_TOO_LARGE` (413) or `UNSUPPORTED` (415).
- The whole command times out after `dcontrol.camera.capture-timeout` (2 min) with `TIMEOUT`, instead of the usual
  15 s.
- **In the web result:**
  - `mime` (`image/jpeg` or `video/mp4`) and `bytes` come from the upload, never from the phone's claim;
  - `durationMs` of a clip is read from the MP4 itself;
  - `width` and `height` are the phone's figures (1–20000).

**The phone's upload:** `PUT /api/device-api/camera/{mediaId}`, with `Authorization: Device id.secret`,
`Content-Type: image/jpeg` or `video/mp4`, and the file as the body. Answers:

| Status | Meaning |
|---|---|
| `204` | Accepted |
| `404` | Unknown, expired or already used slot, or another device's slot |
| `413` | Larger than `dcontrol.camera.max-photo-size` (20 MiB) or `max-video-size` (200 MiB) |
| `415` | A `PHOTO` that isn't a JPEG, a `VIDEO` that isn't an MP4, or a clip longer than the maximum plus 1 s |

The slot is used up even when the upload is rejected, so the waiting command fails at once.

### Camera media (web, REST)

`GET /api/devices/{deviceId}/camera/media/{mediaId}` returns a capture:
- **Success:** `200` with the bytes, the validated `Content-Type`, `X-Content-Type-Options: nosniff`,
  `Cache-Control: private, max-age=1800` and `Content-Disposition: inline` with a file name such as
  `camera-20261030-101530.jpg`.
- **Failure:** `404` `NOT_FOUND` for unknown or expired media, or media of another account.

Captures are kept on disk for `dcontrol.camera.media-retention` (30 min), at most
`dcontrol.camera.max-retained` (20) per phone, oldest removed first. They are also removed when the phone is
unpaired.

## Phase 6 additions (still v1; all additive)

Phase 6 adds a consent-gated remote microphone: live listening and short audio clips. It is the camera session
(Phase 5) without video:

- **Live audio** goes peer to peer over WebRTC, or through the TURN relay. The phone's offer has one audio m-line
  and no video. The controller only relays signaling.
- **Clips** are AAC in an MPEG-4 container (`.m4a`). The phone encodes them from the same microphone samples it
  streams, so live audio keeps playing while a clip records. They pass through the controller's short-lived disk
  spool, never the database.
- **Consent:** the phone owner accepts every session on the phone, and the phone shows an ongoing notification with
  Stop for as long as it runs. See `docs/features/microphone.md`.

### `PERMISSION_REQUIRED` values

`details.permission` also accepts `MICROPHONE`: `RECORD_AUDIO` isn't granted.

### Error code additions

- `RATE_LIMITED` also covers `mic.start` and `mic.capture` for one phone.
- `CONFLICT` also covers:
  - a `mic.start` while a camera or screen session of the phone is using the microphone;
  - a `mic.capture` while the session isn't `LIVE` or another clip is recording.

### The microphone has one user

The phone hands the microphone to one session at a time. A `mic.start` while a camera or screen session with audio
runs fails with `CONFLICT`. A `camera.start` or `screen.start` with `audio: true` while a microphone session runs
starts without audio and answers `{ "audio": false }`.

### Microphone session

```
web                          controller                               phone
 | mic.start (command) -----> | checks owner, limit, audits              |
 |                            | creates sessionId -- mic.start ------->  | (command)
 | <-- mic.start.result ----- | <---------------- mic.start.result --    | {}
 |                            | <---------- mic.state AWAITING_CONSENT   | "Tap to share your microphone"
 | <-- mic.state ------------ |                                          | owner accepts
 | <-- mic.signal OFFER ----- | <------------------- mic.signal OFFER -- | ICE gathered (≤ 4 s)
 | mic.signal ANSWER -------> | ----------------------- mic.signal ----> |
 | <-- mic.state LIVE ------- | <---------------------- mic.state LIVE   |
 | mic.capture -------------> | opens upload slot -- mic.capture ------> | records a clip
 |                            | <----- PUT /api/device-api/mic/{mediaId} | upload
 | <-- mic.capture.result --- | <-------------- mic.capture.result ----- |
 | GET .../mic/media/{id}     |                                          |
 | mic.stop ----------------> | ------------------------ mic.stop -----> |
```

**Session rules:** those of the camera session, with `mic.*` types and `dcontrol.mic.*` settings: non-trickle, the
phone offers, no data channel, one session per phone (a new start ends the old one with `REPLACED`), owned by the
browser socket that started it, ended by the controller on socket close, `DEVICE_OFFLINE` or the consent timeout
(`dcontrol.mic.consent-timeout`, 60 s). A microphone session is independent of camera and screen sessions apart
from the microphone rule above.

**Values:** `state` and `reason` as for the camera session.

#### `mic.start` (web → controller → phone, command)

| Direction | Payload |
|---|---|
| web → controller | `{ "deviceId" }` |
| controller → phone | `{ "sessionId": uuid, "viewer": string ≤ 100, "iceServers": [IceServer, ≤ 8] }` |
| phone → controller result | `{}` |
| controller → web result | `{ "sessionId", "iceServers": [IceServer] }` |

- The phone answers as soon as it has accepted the request, before the owner accepts and before any `mic.state`.
- **Asking for consent:** directly while the app is in the foreground, otherwise through a high-importance "Tap to
  share your microphone" notification. Never automatic.
- **Phone errors:** `PERMISSION_REQUIRED` (`MICROPHONE`; the phone shows nothing), `CONFLICT` (the microphone is in
  use by a camera or screen session), `INTERNAL`.
- **Controller errors:** `RATE_LIMITED` (more than `dcontrol.mic.start-per-minute`, 6 per phone), `NOT_FOUND`,
  `DEVICE_OFFLINE`, `TIMEOUT`.

#### `mic.state` (phone → controller → that browser)

| Direction | Payload |
|---|---|
| phone → controller (event) | `{ "sessionId", "state", "reason": string \| null }` |
| controller → web | `{ "deviceId", "sessionId", "state", "reason" }` |

#### `mic.signal` (both directions)

The same shape and rules as `camera.signal`: `OFFER` from the phone, `ANSWER` from the browser, SDP ≤ 32768.

#### `mic.stop`

The same as `camera.stop`: `{ "sessionId" }` from the browser and to the phone, no result. The phone ends silently.

#### `mic.capture` (web → controller → phone, command)

| Direction | Payload |
|---|---|
| web → controller | `{ "sessionId", "durationMs": int }` |
| controller → phone | `{ "sessionId", "mediaId": uuid, "durationMs" }` |
| phone → controller result | `{ "durationMs": int \| null }` |
| controller → web result | `{ "mediaId", "mime", "bytes", "durationMs", "capturedAt" }` |

- **`durationMs`:** 1000 to `dcontrol.mic.max-audio-duration` (5 min).
- **The controller checks** that the session is `LIVE` and no other clip is recording (`CONFLICT`), and the rate
  limit `dcontrol.mic.capture-per-minute` (20 per phone; `RATE_LIMITED`). Then it opens an upload slot and relays.
- **The phone** records `durationMs` of the live microphone samples as AAC in MPEG-4, uploads it to the slot, then
  answers. Errors: `CONFLICT` (busy or not `LIVE`), `INTERNAL`, and the upload refusals mapped as for
  `camera.capture` (404 → `NOT_FOUND`, 413 → `PAYLOAD_TOO_LARGE`, 415 → `UNSUPPORTED`).
- The command times out after `dcontrol.mic.capture-timeout` (6 min) with `TIMEOUT`.
- In the web result, `mime` (`audio/mp4`), `bytes` and `durationMs` come from the upload, never from the phone.

**The phone's upload:** `PUT /api/device-api/mic/{mediaId}`, with `Authorization: Device id.secret`,
`Content-Type: audio/mp4`, and the file as the body. `204` accepted; `404` unknown, expired, used or another
device's slot; `413` larger than `dcontrol.mic.max-audio-size` (20 MiB); `415` not an MPEG-4 file (`ftyp` at
offset 4), no readable length, or longer than the maximum plus 1 s. The slot is used up even when the upload is
rejected.

### Microphone media (web, REST)

`GET /api/devices/{deviceId}/mic/media/{mediaId}` returns a clip with `Content-Type: audio/mp4`, `nosniff`,
`Cache-Control: private, max-age=1800` and `Content-Disposition: inline` with a name such as
`mic-20261030-101530.m4a`. `404` `NOT_FOUND` for unknown, expired or another account's media. Clips are kept for
`dcontrol.mic.media-retention` (30 min), at most `dcontrol.mic.max-retained` (20) per phone, and removed when the
phone is unpaired or the controller restarts.
