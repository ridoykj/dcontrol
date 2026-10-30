# dcontrol file transfer HTTP API — v1

File contents never travel over the WebSockets (see [ws-v1.md](ws-v1.md)). They are staged on the
controller's disk spool (`dcontrol.storage.dir`) and move in resumable chunks over plain HTTP. The
WebSockets only carry the control messages (`transfer.send`, `transfer.receive`, `transfer.progress`,
`transfer.finished`, `transfer.status`, `transfer.cancel`).

## Transfer resource

```json
{
  "id": "0193…",
  "deviceId": "0193…",
  "direction": "TO_DEVICE",
  "state": "RECEIVING",
  "fileName": "report.pdf",
  "devicePath": "/storage/emulated/0/Download",
  "size": 10485760,
  "receivedBytes": 4194304,
  "sha256": null,
  "errorCode": null,
  "createdAt": "2026-10-29T10:15:30Z",
  "expiresAt": "2026-10-29T10:30:30Z",
  "finishedAt": null
}
```

| Field | Meaning |
|---|---|
| `direction` | `TO_DEVICE` (browser → phone) or `FROM_DEVICE` (phone → browser) |
| `devicePath` | `TO_DEVICE`: target directory on the phone. `FROM_DEVICE`: source file path |
| `receivedBytes` | Bytes the controller has spooled so far (the resume offset for uploads to the controller) |
| `sha256` | Lowercase hex SHA-256 of the spooled file, set when the state becomes `READY` |
| `expiresAt` | When the cleanup job expires the transfer if it hasn't moved on |

### States (FSM `TRANSFER`)

```
∅ ──create──▶ RECEIVING ──stage──▶ READY ──deliver──▶ DELIVERED
RECEIVING | READY ──fail──▶ FAILED
RECEIVING | READY ──cancel──▶ CANCELLED
RECEIVING | READY ──expire──▶ EXPIRED
```

| State | `TO_DEVICE` | `FROM_DEVICE` |
|---|---|---|
| `RECEIVING` | Browser is uploading chunks to the controller | Phone is uploading chunks to the controller |
| `READY` | Staged; phone is told with `transfer.receive` and downloads | Staged; browser can download |
| `DELIVERED` | Phone reported `transfer.finished` with `ok: true` | Browser downloaded the whole file once (it stays downloadable until the spool file is removed) |

Expiry: a `RECEIVING` transfer without progress for 15 min, and a `READY` transfer older than 1 h, become
`EXPIRED`. Spool files are deleted 10 min after a transfer reaches a final state. Final rows are physically
deleted after 7 days.

Limits (`dcontrol.transfers.*`): file size ≤ 4 GiB, chunk ≤ 16 MiB, ≤ 10 GiB of active spool per account,
≤ 3 non-final transfers per device.

## Chunk upload (both directions)

```
PUT <content url>
Content-Range: bytes <start>-<end>/<size>
Content-Length: <end - start + 1>
Content-Type: application/octet-stream
<raw bytes>
```

- `start` must equal the current `receivedBytes`; otherwise `409` with problem code `CONFLICT` and the
  extension property `receivedBytes` (resume from there).
- `size` must equal the transfer's `size`, and `end < size`; otherwise `400 VALIDATION`.
- A chunk larger than 16 MiB → `413 PAYLOAD_TOO_LARGE`.
- Allowed only in `RECEIVING`; otherwise `409 INVALID_STATE_TRANSITION`.
- Response `200` with the `Transfer` JSON. The chunk that completes the file moves the transfer to `READY`.
- A chunk that fails midway is discarded entirely; `receivedBytes` stays at the previous chunk boundary.

To resume after a network error, read the transfer (`GET`) and continue from `receivedBytes`.

## Browser endpoints (Bearer access token)

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/devices/{deviceId}/transfers` | Create. Body `TO_DEVICE`: `{ "direction": "TO_DEVICE", "targetDir", "fileName", "size" }` → `201 Transfer` (works while the phone is offline). Body `FROM_DEVICE`: `{ "direction": "FROM_DEVICE", "path" }` → the controller asks the phone (`transfer.send`) and answers `201 Transfer` with name and size, or the phone's error (`404 NOT_FOUND`, `403 FORBIDDEN`, `409 PERMISSION_REQUIRED`, `400 VALIDATION`), `409 DEVICE_OFFLINE` or `504 TIMEOUT` |
| `GET` | `/api/devices/{deviceId}/transfers` | Transfers of that device from the last 7 days, newest first |
| `GET` | `/api/transfers/{id}` | `Transfer` |
| `PUT` | `/api/transfers/{id}/content` | Chunk upload, `TO_DEVICE` only |
| `POST` | `/api/transfers/{id}/download-ticket` | `FROM_DEVICE` in `READY` or `DELIVERED` → `{ "ticket", "url", "expiresAt" }`. The ticket is single-use and valid 60 s |
| `GET` | `/api/transfers/{id}/content?ticket=…` | No `Authorization` header (meant for a native browser download). Supports `Range: bytes=<start>-`. Headers: `Content-Type: application/octet-stream`, `Content-Disposition: attachment; filename="…"; filename*=UTF-8''…`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`. A complete response moves the transfer to `DELIVERED` |
| `POST` | `/api/transfers/{id}/cancel` | `RECEIVING`/`READY` → `CANCELLED`; the phone gets `transfer.cancel` |
| `GET` | `/api/devices/{deviceId}/app-icons/{sha256}` | `image/png`, `Cache-Control: private, max-age=31536000, immutable`; `404` when the phone never uploaded it |

## Phone endpoints (`Authorization: Device <deviceId>.<deviceSecret>`)

A phone can only reach transfers of its own device; others answer `404`. Wrong or revoked credentials
answer `401`.

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/device-api/transfers/{id}` | `Transfer` |
| `PUT` | `/api/device-api/transfers/{id}/content` | Chunk upload, `FROM_DEVICE` only |
| `GET` | `/api/device-api/transfers/{id}/content` | `TO_DEVICE` in `READY` only. Supports `Range: bytes=<start>-` (`206` + `Content-Range`) so the phone can resume. The phone writes `<name>.part`, verifies `sha256` from `transfer.receive`, renames (adding ` (1)`, ` (2)` … on collisions) and sends `transfer.finished` |
| `HEAD` | `/api/device-api/app-icons/{sha256}` | `200` if the controller already has the icon, else `404` |
| `PUT` | `/api/device-api/app-icons/{sha256}` | Body: PNG bytes (`Content-Type: image/png`), ≤ 32 KiB, SHA-256 of the body must equal the path → `204`. `400 VALIDATION` for a non-PNG body or a hash mismatch, `413` when too large |

Problem details follow [ws-v1.md](ws-v1.md#problem-details).
