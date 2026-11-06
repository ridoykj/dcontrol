# Feature map

Where each feature lives in the three code bases. Package paths are under `com.dcontrol.controller` (controller),
`src/main/frontend/src/features` (web) and `client/` (Android). User docs are `docs/features/<doc>`; the wire
contract is `docs/protocol/ws-v1.md` (`transfers-v1.md` for file contents); the plan and decisions of each phase are
`docs/plans/phase-N.md`.

| Feature | Phase | Wire types | Controller | Web | Android | Doc |
|---|---|---|---|---|---|---|
| Accounts, login, email verification, password reset | 1 | REST only | `auth` | `auth` | (none) | `auth.md` |
| Pairing | 1 | `pairing.completed` | `pairing` | `devices` | `:feature:pairing` | `pairing.md` |
| Connection, presence, ping | 1 | `device.hello/heartbeat/ping`, `hello.ack`, `devices.snapshot`, `device.status` | `device` | `devices` | `:feature:connection`, `:core:websocket`, `:core:network` | `device-connection.md` |
| Device info | 2 | `device.info` | `device` | `devices` (Overview) | `:feature:deviceinfo` | `device-info.md` |
| Files and transfers | 2 | `files.*`, `transfer.*` | `files`, `transfer` | `files` | `:feature:files`, `:core:storage`, `:core:database` | `files.md` |
| Notifications | 2 | `notifications.list`, `notification.*` | `notification` | `notifications` | `:feature:notifications` | `notifications.md` |
| Clipboard | 2 | `clipboard.*` | `clipboard` | `clipboard` | `:feature:clipboard` | `clipboard.md` |
| Contacts | 3 | `contacts.*` | `contacts` | `contacts` | `:feature:contacts`, `:core:telephony` | `contacts.md` |
| Call log, placing calls | 3 | `calls.*`, `call.place`, `telephony.sims` | `calls` | `calls` | `:feature:calllog` | `call-log.md` |
| SMS | 3 | `sms.*` | `sms` | `messages` | `:feature:sms` | `sms.md` |
| Screen mirroring | 4 | `screen.*`, `rtc.signal` | `screen`, `webrtc` | `screen` | `:feature:screen`, `:core:rtc` | `screen-mirroring.md` |
| Remote control | 4 | WebRTC data channel `control` (no WS type) | (none, peer to peer) | `screen` | `:feature:remotecontrol`, `:core:accessibility` | `remote-control.md` |
| Find my phone | 4 | `find.ring/lock/locate` | `findphone` (+ T_DEVICE_LOCATION) | `findphone` | `:feature:findphone` | `find-phone.md` |
| Remote camera | 5 | `camera.*` | `camera`, `media` | `camera` | `:feature:camera`, `:core:rtc` | `camera.md` |
| Remote microphone | 6 | `mic.*` | `mic`, `media` | `mic` | `:feature:mic`, `:core:rtc` | `microphone.md` |

## What each phase stores

Only accounts, devices, pairing tokens, refresh tokens, transfers (rows + spool), app icons and the last location
fix are stored. Notifications, clipboard, contacts, call log and SMS are relayed live and never stored or logged.
Screen, camera and microphone streams never pass through the controller. Camera and microphone captures are disk
files with a 30 min TTL, never database rows.

## Consent and permissions on the phone

| Feature | Runtime grant (`PhonePermission` on the wire) | On-phone consent | Foreground service type |
|---|---|---|---|
| Files | All files access (`PERMISSION_REQUIRED` without a value) | none | runs in `connectedDevice` |
| Notifications | Notification access (no wire value; granted on the phone's card) | none | (listener service) |
| Contacts, call log, SMS | `CONTACTS`, `CALL_LOG`, `PHONE`, `SMS` | none | none |
| Screen | none | the system MediaProjection dialog, every session | `mediaProjection` (+ `microphone`) |
| Remote control, lock | Accessibility service (`ACCESSIBILITY`) | none | none |
| Location | `LOCATION` | none | none |
| Camera | `CAMERA` (+ `RECORD_AUDIO` for sound) | dcontrol's prompt every session | `camera` (+ `microphone`) |
| Microphone | `RECORD_AUDIO` (`MICROPHONE`) | dcontrol's prompt every session | `microphone` |

The exact `PhonePermission` values are in `common/ws/PhonePermission.java` and the client's `Permissions` object;
the web maps them to cards in `features/devices/components/PermissionRequired.tsx`.
