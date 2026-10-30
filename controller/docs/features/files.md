# Files

The web UI browses the phone's storage, creates, renames and deletes files and folders, and moves files in
both directions. Browsing goes over the device WebSocket; file contents go over HTTP through a disk spool on
the controller, so they never pass through JSON. Wire contracts:
[`ws-v1.md`](../protocol/ws-v1.md#phase-2-additions-still-v1-all-additive) (commands) and
[`transfers-v1.md`](../protocol/transfers-v1.md) (contents).

## Browsing

| Web action | Relayed command | Notes |
|---|---|---|
| Open the Files tab | `files.roots` | Storage volumes with free/total space |
| Open a folder | `files.list {path, offset}` | Folders first, then by name. The phone fills a page up to ~48 KiB and returns `nextOffset`; the table shows "Load more" |
| New folder | `files.mkdir {path, name}` | |
| Rename | `files.rename {path, newName}` | |
| Delete (one or many) | `files.delete {paths}` | Recursive for folders; per-path failures are reported |

The phone limits every path to the `StorageManager` volume roots after canonicalizing it (no `..`
escapes, no symlinks out of a volume) and answers `FORBIDDEN` otherwise. Without storage access it answers
`PERMISSION_REQUIRED`, and the web shows how to grant it. The controller validates every result before
it reaches the browser: names, paths, sizes and list lengths.

## Transfers

```
Upload (browser → phone)
  POST /api/devices/{id}/transfers {TO_DEVICE, targetDir, fileName, size}     → RECEIVING
  PUT  /api/transfers/{id}/content  8 MiB chunks, Content-Range, resumable    → READY (SHA-256 computed)
  WS   transfer.receive {transferId, targetDir, fileName, size, sha256}  → phone
  GET  /api/device-api/transfers/{id}/content  (Range resume) ; phone verifies hash, renames .part
  WS   transfer.finished {ok: true}                                           → DELIVERED

Download (phone → browser)
  POST /api/devices/{id}/transfers {FROM_DEVICE, path}  → WS transfer.send; waits for name and size
  PUT  /api/device-api/transfers/{id}/content  chunks from the phone           → READY
  POST /api/transfers/{id}/download-ticket  → GET /api/transfers/{id}/content?ticket=…  (native download)
                                                                               → DELIVERED
```

- Every state change is pushed to the browser as `transfer.status`. Progress (`transfer.progress`) arrives
  at most once per second.
- Uploads survive network drops. The uploader reads `receivedBytes` and continues from there; a chunk at
  the wrong offset gets `409` with `receivedBytes`.
- A file uploaded while the phone is offline stays READY for an hour. The controller offers it again
  (`transfer.receive`) as soon as the phone connects. Phones resume by `transferId`.
- Limits (`dcontrol.transfers.*`): 4 GiB per file, 16 MiB per chunk, 10 GiB of open transfers per
  account, 3 open transfers per device.
- Cleanup:
  - Stalled transfers (no progress for 15 min) and READY ones older than 1 h expire.
  - Spool files go 10 min after a transfer ends.
  - Rows are physically deleted after 7 days.

## Security

- Downloads are always `application/octet-stream` with `Content-Disposition: attachment`,
  `X-Content-Type-Options: nosniff` and `Cache-Control: no-store`. File names are stripped of control
  characters and path separators. Content from the phone is never rendered inline.
- The download ticket is single-use, valid 60 s, and bound to one transfer. The page never puts the access
  token in a URL.
- A phone authenticates with its device secret and only reaches its own device's transfers. Other
  transfers answer `404`.

## Android permissions

| Android version | Permission | How it is granted |
|---|---|---|
| 11+ (API 30+) | `MANAGE_EXTERNAL_STORAGE` ("All files access") | Special app access. The Files access card on the status screen opens `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`; checked with `Environment.isExternalStorageManager()` |
| 8–10 (API 26–29) | `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=29`), plus `requestLegacyExternalStorage` on API 29 | Runtime permission with a rationale |

- `Android/data` and `Android/obb` stay inaccessible on 11+ even with All files access.
- ⚠ Google Play only accepts `MANAGE_EXTERNAL_STORAGE` for apps whose core feature is file management,
  after a declaration form.

**Foreground service:** transfers run inside the existing `ConnectionService`
(`foregroundServiceType="connectedDevice"`); there is no new type. A "Transfers" notification channel shows
progress (`POST_NOTIFICATIONS`, requested in Phase 1).
