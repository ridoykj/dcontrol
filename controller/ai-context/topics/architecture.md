# Architecture

dcontrol lets a user manage their Android phone from a browser. There are two apps:

- `controller/`: one Spring Boot 4.1 app (Java 25). It serves the REST API, both WebSocket endpoints, and
  the built React SPA (`src/main/frontend` → `src/main/resources/static`).
- `client/`: the Android app (Kotlin, Compose). See `android-client.md`.

The controller relays between browser and phone. The phone always dials out, so NAT doesn't matter.

```
Browser ──REST /api/**──────────┐
Browser ──WS /ws/web (ticket)───┤ controller ──WS /ws/device (device secret)── Phone (foreground service)
                                └── PostgreSQL 18 (Flyway), Mailpit/SMTP
```

## Backend packages (`com.dcontrol.controller`)

| Package | Contents |
|---|---|
| `common.context` | `Actor` (USER / DEVICE / SYSTEM / ANONYMOUS) and `ActorContext`: who is acting on this thread |
| `common.persistence` | `BaseEntity` (header), `EventAwareTransactionManager` (opens a T_EVENT row per write tx), `ActorTx` (run as an actor in a new tx), `Uuid7`, `SystemKeys` |
| `common.tenancy` | `AccountScopedEntity` (`@TenantId id_account_key`), `AccountTenantResolver` |
| `common.fsm` | `StateMachine`, `FsmKey`, `FsmKeyConverter`, `StatefulEntity` |
| `common.crud` | `CrudService` (list / get / logical delete / restore), `ScopedRepository` |
| `common.error` | `ErrorCode`, `ApiException`, `ProblemDetailsHandler`, `Problems` |
| `common.security` | `SecurityConfig`, `JwtService`, `RateLimiter`, `SecureTokens`, problem-shaped 401/403 |
| `common.ws` | Envelope (errors may carry `details.permission`), `MessageCodec`, `MessageType`, close codes, session registries, `PendingRequestRegistry`, `RelayedCommand` (+ `BeforeRelay` hook), `RelayException` (`toApiException`), `PhonePermission` |
| `common.blob` | `PhoneBlobRelay`: single-use in-memory upload slots a phone fills on request (contact photos); never on disk |
| `common.validation` | `DevicePath`, `FileName`, `PhoneNumber`, `DialNumber` (no `*`/`#`), `PageCursor` |
| `auth` | Account, User, RefreshToken, `AuthService`, mail |
| `pairing` | PairingToken + FSM, `PairingService`, web and device-api controllers, cleanup job |
| `device` | Device + FSM, DeviceStatus, `PresenceService`, socket handlers, `DeviceCommandRelay`, `RelayedCommands`, `RelayGuards` (per-device limit + audit line), `DeviceEventHandler`, `BrowserMessageHandler` (+ `ClosedListener`), `DeviceConnectionListener` (ready / disconnected), `DeviceApiAuth`, `DeviceBlobController`, ws tickets |
| `webrtc` | `IceServers` (per-socket ICE list, `{host}` from the handshake), `TurnCredentials` (coturn REST scheme) |
| `screen` | `ScreenSessionService` (in-memory sessions bound to one browser socket and one phone; consent timeout; cleanup on close), `ScreenProtocol`, `ScreenConfig` |
| `findphone` | `find.ring` / `find.lock` relays, `DeviceLocation` (T_DEVICE_LOCATION, last fix per device), `LocationService`, `LocationController` |
| `files`, `notification`, `clipboard`, `contacts`, `calls`, `sms` | Payload records and the `RelayedCommand` / `DeviceEventHandler` beans of each feature; `AppIconStore`; `ContactPhotoController` |
| `transfer` | `FileTransfer` + FSM TRANSFER, `TransferService`, `TransferSpool` (disk), download tickets, browser and device controllers, cleanup job |
| `spa` | Forwards client-side routes to `index.html` |

## Request flow and the actor

1. HTTP: `RequestIdFilter` sets `traceId`, Spring Security validates the bearer JWT, and `ActorContext.current()`
   derives `Actor.user(aid, sub)` from it.
2. Transactions: `EventAwareTransactionManager.doBegin` inserts T_EVENT for the actor and sets
   `dc.evt_key` / `dc.usr_key` in transaction-local settings. Header triggers read them.
3. Tenancy: Hibernate's `@TenantId` filter uses `AccountTenantResolver`, which follows the actor. The
   SYSTEM actor is "root" and sees every account; ANONYMOUS sees nothing.
4. Code that runs without an HTTP principal (login lookups, sockets, jobs) wraps its work in
   `actorTx.read/write(actor, …)`. **Bind the actor before the transaction starts**, because Hibernate
   resolves the tenant when the session opens.

## Events

Services publish `DeviceEvents.*` inside transactions. `DeviceEventRelay` handles them
`@TransactionalEventListener(AFTER_COMMIT)` and pushes to sockets, so nothing is announced for a transaction
that rolls back. Mail is sent the same way (`MailService.OutgoingMail`).

## Frontend (`src/main/frontend/src`)

| Path | Contents |
|---|---|
| `routes/` | TanStack Router file routes; `routeTree.gen.ts` is generated, never edit it |
| `features/auth`, `features/devices`, `features/files`, `features/notifications`, `features/clipboard`, `features/contacts`, `features/messages`, `features/calls`, `features/screen`, `features/findphone` | `api/`, `components/`, `hooks/`, `lib/`, `pages/`, `schemas/` (zod) |
| `shared/api` | `client.ts` (fetch + refresh), `session.ts` (in-memory token), `problem.ts` |
| `shared/ws` | `RealtimeClient` (reconnect, requestId correlation, fire-and-forget `send`; `RealtimeError.permission`), `RealtimeProvider`, `useRealtimeEvent` (typed push subscriptions), `useRelayedPages` (cursor-paged phone lists on `useInfiniteQuery`) |
| `shared/components/ui/shadcn` | Vendored React Aria Tailwind components (`@react-aria` shadcn registry); regenerate with the shadcn CLI |

Server state lives in TanStack Query. `useDeviceRealtimeSync` patches the device queries from
`device.status` pushes.

## Adding a phone feature (Phase 2 and 3 pattern)

- **Browser → phone command:** add a `MessageType`, payload records with Bean Validation (the browser record
  implements `RelayedCommand.Targeted`, the phone result record is validated too), and a `RelayedCommand`
  bean. `BrowserSocketHandler` finds it through `RelayedCommands`; `DeviceCommandRelay` checks ownership,
  maps request ids, times out, allow-lists the phone's error codes and validates the result.
- **Phone → browser event:** add a `DeviceEventHandler` bean. The handler gets the authenticated
  `Source(accountKey, deviceKey)`; never trust a device id inside the payload.
- **Controller-originated command:** `DeviceCommandRelay.send(deviceKey, type, payload)` returns a future
  that fails with `RelayException` (offline, disconnected, timeout, or the phone's code).
- **Binary data:** HTTP only. File contents go through `TransferSpool`; phones authenticate HTTP calls with
  `DeviceApiAuth` (`Authorization: Device id.secret`).
- **Run on connect:** implement `DeviceConnectionListener` (called after the first `hello.ack`).
- **Guarded commands:** `RelayedCommand.withBeforeRelay(...)` runs after the ownership check. `RelayGuards.limited` (per-device `RATE_LIMITED` + audit line) guards `sms.send` and `call.place`; `RelayGuards.audited` logs `contacts.delete` and `calls.delete`. Audit lines carry the type, device and user, never the payload.
- **Missing phone access:** a phone answers `PERMISSION_REQUIRED` with `details.permission` (`PhonePermission`); the relay keeps only allow-listed values and the web shows `PermissionRequired` for it.
- **Small binaries on request:** `PhoneBlobRelay.open(deviceKey)` + a controller → phone command carrying the `uploadId`; the phone PUTs to `/api/device-api/blobs/{uploadId}` and the waiting request streams the bytes. Nothing is written to disk.
- **Live personal data:** contacts, call log and SMS are never stored. Lists page with phone-made opaque cursors; `*.changed` hints make the web refetch.
- **Session-routed messages (Phase 4):**
  - Use these when a phone message must reach one browser tab only. Keep a registry entry that binds the session
    to the browser socket id and the device, and send with `WebSessionRegistry.send(account, socketId, text)`.
  - Browser messages that need the socket itself (not a request/result relay) are `BrowserMessageHandler` beans,
    checked before `RelayedCommands`.
  - A session id naming another socket's or another device's session is a protocol violation. An unknown one is
    ignored, because it can race with the end of the session.
- **Media never touches the controller:** screen video, audio and remote input go peer to peer, or through coturn.
  The controller relays only the one SDP offer and the one answer of each session.

## Not built yet

- Running more than one controller instance (registries, rate limiter, tickets, transfer spool and icons are
  local).

The device page (`routes/_authenticated/devices/$deviceId/`) is a layout route with tabs. Overview, Screen,
Files, Notifications, Clipboard, Contacts (`?q=&id=&key=`), Messages (`?thread=` or `?to=`), Calls (`?type=`) and
Find are child routes, and the Files route keeps the open folder in `?path=`. Uploads
use `uploadInChunks` (8 MiB chunks, resume from the server's `receivedBytes`). Downloads wait for
`transfer.status` READY, then open the ticket URL as a native download.
