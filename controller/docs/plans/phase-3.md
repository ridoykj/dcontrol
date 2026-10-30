# Phase 3: contacts, call log, SMS

Status: implemented on 2026-10-29. It passes `./mvnw verify` (125 JUnit + 63 Vitest, frontend build) and
`./gradlew testDebugUnitTest lintDebug assembleDebug` (145 tests). Not yet checked on an emulator.

## Decisions

| Topic | Decision |
|---|---|
| Storage | Read live from the phone and never stored on the controller (like notifications). No migration |
| SMS | Read threads and messages, send through the phone, live incoming via `ContentObserver`. MMS shown as a placeholder only. No `RECEIVE_SMS`, no SMS role |
| Contacts | Read, create, edit (name, numbers, emails, company, title, note, favorite) and delete. No photo editing |
| Calls | Read the call log, delete entries, place calls. `DialNumber` rejects `*` and `#` (no MMI/USSD) |
| Contact photos | In-memory HTTP pull relay (`PhoneBlobRelay`); binary stays out of the WebSocket and off the disk |
| Guardrails | `sms.send` 10/min and `call.place` 3/min per phone; audit lines for sends, calls and deletes, never with payloads |
| Foreground service | No new type; everything runs in the `connectedDevice` `ConnectionService` |

## What was added

- **Protocol (additive, still v1):**
  - commands `contacts.*`, `calls.*`, `call.place`, `telephony.sims`, `sms.*`;
  - events `sms.received`, `sms.status`, `sms.changed`, `calls.changed`, `contacts.changed`;
  - opaque `cursor` paging;
  - `error.details.permission` on `PERMISSION_REQUIRED`;
  - `contacts.photo` + `PUT /api/device-api/blobs/{uploadId}`.

  See [`ws-v1.md`](../protocol/ws-v1.md#phase-3-additions-still-v1-all-additive).
- **Controller:** `contacts`, `calls`, `sms` packages; `common.blob.PhoneBlobRelay`; `RelayedCommand.BeforeRelay`
  with `RelayGuards`; validators `PhoneNumber`, `DialNumber`, `PageCursor`; `RelayException.toApiException`
  shared with transfers.
- **Web:** Contacts, Messages and Calls tabs; `useRelayedPages`; `PermissionRequired`; `PlaceCallDialog`;
  SMS part counter.
- **Feature docs:** [contacts](../features/contacts.md), [call log](../features/call-log.md), [SMS](../features/sms.md).

## Known limits

- Data is only available while the phone is online; there is no server-side search or history.
- No MMS content, no marking as read and no SMS deletion (these need the default SMS role).
- No contact photo editing; the form doesn't edit middle name, prefix, suffix, addresses or events (they are kept).
- Sending goes to one number; group threads are read-only.
- Rate limits and blob slots are in memory (single controller instance).
