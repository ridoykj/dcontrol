# Phase 2: files, notifications, clipboard, device info

Status: implemented on 2026-10-29. It passes `./mvnw verify` (controller + web) and
`./gradlew testDebugUnitTest lintDebug assembleDebug` (client).

## Decisions

| Topic | Decision |
|---|---|
| File access on the phone | "All files access" (`MANAGE_EXTERNAL_STORAGE`) on Android 11+; legacy storage permissions on 8–10 |
| Notifications | Live only, never stored; dismiss and inline reply from the web |
| File contents | Disk spool on the controller (`dcontrol.storage.dir`), resumable chunked HTTP in both directions |
| App icons | Content-addressed PNG files on disk. Not in the database, because the column conventions have no binary prefix |
| Clipboard | Text only; phone → web needs a user action because of Android 10+ background-read limits |
| Foreground service | No new type; transfers run in the `connectedDevice` `ConnectionService` |
| Android database | Room 3.0 (`androidx.room3`) for resumable transfers and per-app notification switches |

## What was added

- **Protocol (additive, still v1):**
  - commands relayed through a `RelayedCommand` registry: `device.info`, `files.*`, `notifications.list`,
    `notification.dismiss`, `notification.reply`, `clipboard.set`;
  - phone events routed by `DeviceEventHandler` beans;
  - transfer control messages.

  See [`ws-v1.md`](../protocol/ws-v1.md) and [`transfers-v1.md`](../protocol/transfers-v1.md).
- **Migration `V5__file_transfer.sql`:** FSM `TRANSFER` (keys `…03xx`) and `T_FILE_TRANSFER`, not audited.
- **Web:** the device page has tabs for Overview (with live details), Files, Notifications and Clipboard.
- **Feature docs:** [files](../features/files.md), [notifications](../features/notifications.md),
  [clipboard](../features/clipboard.md), [device info](../features/device-info.md).

## Known limits

- Single controller instance: the spool, app icons, socket registries and download tickets are local.
- Folders can't be downloaded as one archive; select their files instead.
- Transfers only make progress while the phone's foreground service runs.
