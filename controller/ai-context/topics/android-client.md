# Android client

Source: `client/` (Gradle, Kotlin, Jetpack Compose, Hilt, Room 3). Package root `com.dcontrol.client`.
Contract with the controller: `controller/docs/protocol/ws-v1.md` and `controller/docs/protocol/transfers-v1.md`.

## Module map

| Module | Kind | Responsibility |
|---|---|---|
| `:app` | Android app | `DControlApplication` (`@HiltAndroidApp`), `MainActivity`, `navigation/AppRoot` (NavHost, feature cards on the status screen), `onboarding/` |
| `:core:websocket` | **Kotlin/JVM** (no Android) | `protocol/` (envelope, Phase 1 + `Phase2Protocol.kt` payloads, `MessageTypes`, `ErrorCodes`, `CloseCodes`, `ProtocolCodec`), `Backoff`, `DeviceSocket` (`sendEvent`), `ConnectionState`, `DeviceCredentials`, `CommandHandler`, `EventHandler`, `DeviceEventSender`, `typedCommand` |
| `:core:network` | Android library | `NetworkModule` (OkHttpClient with 20 s ping, Json, `ProtocolCodec`), `RetrofitFactory`, `ApiException` + `apiCall`, `credentials/`, `device/DeviceInfoProvider`, `device/DeviceHttpClient` (`Authorization: Device …` for `/api/device-api/**`) |
| `:core:storage` | Android library | `StorageRoots` (mounted `StorageManager` volumes, blocked `Android/data`/`obb` on 30+), `StorageAccess` (All files access / legacy permissions), `openAllFilesAccessSettings` |
| `:core:database` | Android library | Room 3 (`androidx.room3`) `DControlDatabase`: `transfers` (`TransferEntity`, `TransferDao`), `mirrored_apps` (`MirroredAppEntity`, `MirroredAppDao`) |
| `:core:designsystem` | Android library | `DControlTheme`, `StatusColors`, `StatusIndicator`, `SectionCard`, `LabeledValue` |
| `:core:permissions` | Android library | `rememberPermissionRequester`, `PermissionRationale`, `openAppSettings`, `openBatteryOptimizationSettings` |
| `:feature:pairing` | feature | QR scan, preview/confirm, claim |
| `:feature:connection` | feature | `ConnectionService`, `DeviceConnectionManager`, `SocketEventSender`, `BootReceiver`, status screen (with a `featureCards` slot), "Send clipboard" notification action |
| `:feature:files` | feature | `data/FileSystem` (root-confined, canonicalized paths, byte-budget paging), `data/FilesCommands` (`files.*`, `transfer.send/receive`, `transfer.cancel`), `transfer/TransferEngine` + `TransferApi`, `ui/FilesAccessCard` |
| `:feature:notifications` | feature | `service/MirrorListenerService`, `data/NotificationMapper`, `NotificationMirror`, `EventCoalescer`, `AppIcons`, `NotificationCommands`, `ui/NotificationAccessCard`, `ui/MirroredAppsScreen` |
| `:feature:clipboard` | feature | `data/Clipboard` (`clipboard.set`, `sendCurrentClip`), `ui/ClipboardSendActivity`, `ui/ClipboardTileService`, `ui/ClipboardCard` |
| `:feature:deviceinfo` | Android library | `DeviceInfoCollector` (`device.info`) |
| `:core:telephony` | Android library | `RuntimePermissions` + `require(group, …)` (`PERMISSION_REQUIRED` with `details.permission`), `PermissionSets`, `ContentQueries` (seam over `ContentResolver`; `mapRows`, lazy `rows()`), `ProviderChanges` (debounced `ContentObserver`), `ContactNames` (PhoneLookup LRU), `SimCards`, `whenGranted` |
| `:feature:contacts` | feature | `data/ContactsStore`, `ContactFieldsValidator`, `ContactAccounts`, `ContactsCommands` + `ContactsWatcher`, `ContactPhotoUploader` (+ `PhotoEncoder`), `ui/ContactsAccessCard` |
| `:feature:calllog` | feature | `data/CallLogStore`, `TelecomGateway`, `CallCommands` (`calls.*`, `call.place`, `telephony.sims`) + `CallLogWatcher`, `ui/CallsAccessCard` |
| `:feature:sms` | feature | `data/SmsStore`, `SmsGateway`, `SmsStatusTracker`, `SmsReportIntents`, `SmsStatusReceiver`, `SmsCommands` + `SmsWatcher`, `ui/SmsAccessCard` |
| `:core:accessibility` | Android library | `AccessibilityBridge` (`@Singleton` service ⇄ feature seam; `available: StateFlow`, `actions`), `ControlActions` (normalized `down/move/up/wheel/navKey/text/edit`), `NavKey`/`EditKey`, `isAccessibilityServiceEnabled`, `openAccessibilitySettings` |
| `:feature:remotecontrol` | feature | `service/ControlAccessibilityService` (implements `ControlActions`, registers into the bridge), `domain/GestureStreamer` (normalized → display-pixel `GesturePath`), `domain/TextInjector` (`EditableNode` seam, `ACTION_SET_TEXT`/`ACTION_IME_ENTER`), `ui/RemoteControlCard` |
| `:core:rtc` | Android library | `RtcEngine`/`RtcEngineFactory`/`RtcConfig` seam with an `RtcSource` (video capturer, size, frame rate, bitrate, optional audio module), `WebRtcEngine`, `GatheringTracker`, `ControlOutbox`, `di/RtcModule` (the one `RtcEngineFactory` binding) |
| `:feature:screen` | feature | `data/ScreenCommands` (`screen.start`, `rtc.signal`, `screen.stop`), `domain/ScreenController` + `ScreenSession` + `ScreenConsentFlow`, `rtc/ScreenRtcSource` (`ScreenCapturerAndroid` + `AudioPlaybackCapture`), `control/` (parser, router, `PhoneControl`), `service/ScreenConsentActivity`/`ScreenCaptureService`/`ScreenNotifications`, `ui/ScreenShareCard` |
| `:feature:camera` | feature | `data/CameraCommands` (`camera.start`/`signal`/`stop`/`capture`/`switch`) + `CameraUploader`, `domain/CameraController` + `CameraSession` + `CameraConsentFlow`, `capture/CameraCapture` seam + `CameraXCapture` (Preview + ImageCapture + VideoCapture) + `CameraXVideoCapturer` (CameraX preview → WebRTC), `service/CameraConsentActivity`/`CameraCaptureService`/`CameraNotifications`, `ui/CameraShareCard` |
| `:feature:findphone` | feature | `data/FindCommands` (`find.ring`/`find.lock`/`find.locate`), `domain/Ringer` (+ `TonePlayer`/`RingNotifier`), `Locator` (`LocationManagerCompat`), `Locker` (+ `LockNotifier`), `service/RingStopReceiver`, `ui/FindPhoneCard` |

Features never depend on each other; the screen and find-phone features reach the Accessibility service only
through `:core:accessibility`. Shared types live in `core/*`. `:app` wires the nav graphs together and passes
the files/notifications/clipboard/contacts/calls/SMS/screen/camera/remote-control/find cards into
`connectionScreen(featureCards = …)`. The connection notification reaches `ClipboardSendActivity` through the
action string `com.dcontrol.client.action.SEND_CLIPBOARD`.

## Conventions

- Convention plugins in `build-logic/`: `dcontrol.android.application|library|compose|hilt|feature`,
  `dcontrol.jvm.library`. All versions come from `gradle/libs.versions.toml`.
- AGP 9 built-in Kotlin: don't apply `org.jetbrains.kotlin.android`. compileSdk/targetSdk 37, minSdk 26, Java 17 bytecode.
- Features: `data/`, `domain/`, `ui/`. UI = `@HiltViewModel` exposing `StateFlow<…UiState>` (immutable data class)
  + a stateless screen composable + a `…Route` composable that wires the ViewModel.
- Screens expose test tags; Compose tests run with Robolectric (`src/test/resources/robolectric.properties`
  pins `sdk=36` in every module with Robolectric tests).
- Type-safe navigation with `@Serializable` destinations.
- Room 3: DAOs are `suspend`/`Flow` only; `exportSchema = false` while the schema is at version 1.

## Command and event plumbing

- A feature contributes handlers with Hilt multibindings:
  `@Provides @IntoMap @StringKey(MessageTypes.X) fun x(...): CommandHandler` (or `EventHandler` for messages
  without `requestId`, e.g. `transfer.cancel`). `ConnectionModule` injects both maps into `DeviceSocket`;
  `@Multibinds` keeps empty maps legal.
- `typedCommand(codec, requestSerializer, resultSerializer) { … }` decodes the payload (a missing payload is `{}`),
  answers `VALIDATION` when it doesn't parse, and encodes the result. Throw `CommandException(ErrorCodes.X, msg)`
  for an error result. Phones may only use the error codes in `ErrorCodes`.
- Handlers send phone → controller events through `DeviceEventSender` (`SocketEventSender` wraps
  `Provider<DeviceSocket>` because handlers are built while the socket is). `send` returns false unless the
  socket completed `hello.ack`; events are best-effort and the web re-syncs with `*.list`.

## Files and transfers

- `FileSystem` validates every path (absolute, ≤ 4096 chars, no NUL), canonicalizes it (resolving `..` and
  symlinks) and requires it inside a `StorageRoots` volume and outside `blockedPaths()`. Roots can't be renamed
  or deleted. `files.list` sorts directories first, then by name, and fills pages up to 48 KiB of encoded entries.
- Every `files.*` and `transfer.*` command answers `PERMISSION_REQUIRED` without storage access.
- `TransferEngine` (own IO scope, singleton) is idempotent by `transferId`:
  - `transfer.send` (FROM_DEVICE): answers `{fileName, size, modified}`, then uploads 8 MiB chunks with
    `Content-Range`, resuming from `GET …/transfers/{id}` `receivedBytes` and from a `409 CONFLICT`'s
    `receivedBytes`. Success is not reported by the phone (the controller stages the file on the last chunk);
    a vanished or changed source is reported as `transfer.finished {ok:false}`.
  - `transfer.receive` (TO_DEVICE): checks the target dir and free space, downloads to
    `<fileName>.<transferId>.part` with `Range: bytes=<n>-`, verifies SHA-256, renames to a free name
    (`name (1).ext`, …), runs MediaScanner, sends `transfer.progress` (≤ 1/s) and `transfer.finished`.
    A completed download keeps its Room row (`completedName`) until `transfer.finished` is actually sent, so a
    re-sent `transfer.receive` after a reconnect reports again instead of downloading a second copy.
  - `transfer.cancel` stops the job and deletes the `.part` file. `404`/`410`/non-offset `409` → the transfer is
    dropped. Network errors pause; stored transfers resume on every `Online` state and when the controller
    re-sends the commands after `hello.ack`.
- Progress notifications use the "Transfers" channel (`AndroidTransferNotifier`), only when POST_NOTIFICATIONS is granted.
- Transfers run inside the existing `connectedDevice` foreground service's process; no new FGS type.

## Notifications

- `MirrorListenerService` (bound by the system after Notification access is granted) forwards to
  `NotificationMirror`. Nothing is persisted except the per-app toggle list.
- Own notifications, group summaries and apps switched off in "Mirrored apps" are never sent.
- `NotificationMapper` reads the app label/icon from the hidden `android.appInfo` extra
  (`Notification.EXTRA_BUILDER_APPLICATION_INFO`), so no `QUERY_ALL_PACKAGES`. Truncates to the protocol limits.
- `EventCoalescer` sends at most one `notification.posted` per key per 250 ms (newest wins).
- `AppIcons` renders a ≤ 32 KiB PNG (96/64/48 px), includes its SHA-256 as `iconHash`, and uploads it once via
  `HEAD`/`PUT /api/device-api/app-icons/{sha256}`.
- `notification.reply` fills every free-form `RemoteInput` of the first reply action and sends its `PendingIntent`.

## Clipboard

- `clipboard.set` writes the clip from the background.
- Android 10+ only lets the focused app read the clipboard, so phone → web (`clipboard.changed`) happens only from
  the in-app card, the Quick Settings tile or the notification action; the last two open the translucent
  `ClipboardSendActivity`, which reads in `onWindowFocusChanged(true)` and finishes. Text is cut to 16 000 chars.

## Contacts, call log and SMS (Phase 3)

**Shared**
- All three are read live through `ContentQueries`; nothing is cached except the `ContactNames` LRU, which is
  cleared on every contacts change.
- Value helpers live in `:core:websocket` (`Phase3Values.kt`):
  - `DialNumbers.normalize`: only `+` and 2–20 digits after stripping separators, so `*` and `#` are never dialled;
  - `Phase3Text` (cleaning, truncation, ms timestamps);
  - `Cursors` (opaque base64url cursors, ≤ 256 chars);
  - `fillPage` (48 KiB page budget).
- **Permissions:** every command first calls `permissions.require(Permissions.X, …)`, which answers
  `PERMISSION_REQUIRED` with `details.permission`. `CommandException.permission` is encoded into
  `error.details` only for that code. No telephony → `UNSUPPORTED`.
- **Watchers:**
  - `OnlineWatcher` (`:core:websocket`) is contributed with `@IntoSet`.
  - `DeviceConnectionManager` runs every watcher through `launchOnlineWatchers` while the socket is `Online`,
    and cancels them on disconnect.
  - Each watcher waits for its permission with `whenGranted` (polls every 30 s), then collects
    `ProviderChanges` (1 s debounce).

**Contacts**
- **Listing:** `contacts.list` reads `_id/lookup/display_name/sort_key` for the whole (filtered) list, sorted
  by `sort_key, _id`. The cursor is `(sort_key prefix, id)`; paging continues after that id, or after the
  collated position if the contact is gone. Phones and emails come from one `Data` query per 400 ids.
- **Version and read-only:** `version` = `rawId:VERSION,…` (SHA-256 hex if it exceeds 256 chars). A raw
  contact is read-only when its account type has a contacts sync adapter that doesn't upload; null accounts
  and accounts without an adapter are writable.
- **Update** is one `applyBatch`:
  1. assert every raw contact's `VERSION`;
  2. delete the editable mimetypes (name, phone, email, organization, note) from every writable raw contact;
  3. update `STARRED`;
  4. insert the new rows on the first writable raw contact, skipping numbers and emails a read-only raw
     contact already shows.

  A failed assert gives `CONFLICT`. Phone types outside the enum (car, assistant…) are read as `CUSTOM` with
  the system label.
- **Create and delete:** create inserts a raw contact (account from `contacts.accounts` only, or the
  phone-only null account) plus back-referenced data rows. Delete deletes the aggregate contact URI.
- **Photo:** `contacts.photo` reads `openContactPhotoInputStream(preferHighres)` and re-encodes it as a JPEG
  of ≤ 720 px and ≤ 256 KiB (dropping metadata). It then `PUT`s to `/api/device-api/blobs/{uploadId}`:
  404 → `NOT_FOUND`, 413 → `PAYLOAD_TOO_LARGE`.

**Call log**
- **Listing:** `calls.list` queries `CallLog.Calls` with the `limit` URI parameter (501) and a `(date, _id)`
  keyset.
- **Rows:** withheld numbers (presentation ≠ ALLOWED) are `null`. SIM labels come from `PHONE_ACCOUNT_ID`
  (a subscription id or ICCID).
- **Placing calls:** `call.place` uses `TelecomManager.placeCall` with `EXTRA_PHONE_ACCOUNT_HANDLE` for
  `simId`. The handle is matched with `TelephonyManager.getSubscriptionId(handle)` on API 30+, or by handle
  id before. A `simId` needs READ_PHONE_STATE.

**SMS**
- **Threads:** `content://mms-sms/conversations?simple=true` with a `(date, _id)` keyset; recipients come
  from `canonical-addresses`.
  - unread counts come from one `read = 0` inbox query;
  - `hasMms` comes from one `Mms` `thread_id` query;
  - if the URI throws or returns null (some OEMs), threads are built by grouping `content://sms`;
  - threads without a usable address are skipped.
- **Messages:** SMS rows and MMS rows (`Mms.DATE` is in seconds) are merged lazily from two cursors. The order
  is date DESC, SMS before MMS within the same millisecond, then id DESC; the cursor is `(dateMs, kind, id)`.
  For MMS only the `Mms` row is read, plus the addr table (FROM for inbox, TO otherwise) for messages on the
  page. `mmsSummary` = `MMS` or `MMS · <subject>`.
- **Sending:** `sms.send` → `SmsManager.divideMessage`; more than 20 parts → `PAYLOAD_TOO_LARGE`. It then calls
  `sendMultipartTextMessage` with per-part `PendingIntent`s to the unexported `SmsStatusReceiver`:
  - sent intents are immutable;
  - delivered intents are mutable and explicit, because the PDU is filled in.

  `SmsStatusTracker` keeps ≤ 100 sends in memory and sends one `sms.status` per stage: SENT after every part,
  FAILED on the first failure (with the `SmsManager` result name), DELIVERED after every part's report.
- **Watching:** `SmsWatcher` remembers the max inbox `_id`. On every SMS/MMS change it sends `sms.received`
  for up to 20 new inbox rows, otherwise `sms.changed`.

**Cards** (`ContactsAccessCard`, `CallsAccessCard`, `SmsAccessCard`) use `rememberRuntimePermissionsState`
(`:core:permissions`):
- it requests the whole group;
- `blocked` (denied without a rationale) switches the button to app settings;
- `mayNeedRestrictedSettings()` (API 35+, not installed by Play) shows the "Allow restricted settings" hint
  on the SMS card.

## Screen mirroring, remote control, find my phone (Phase 4)

- **Protocol** (`Phase4Protocol.kt`, `MessageTypes`, `Permissions`): `screen.start` (command), `screen.stop` +
  `rtc.signal` ANSWER (controller → phone events), `screen.state` + `rtc.signal` OFFER (phone → controller
  events), `find.ring`/`find.lock`/`find.locate` (commands). Enum-like values are the UPPERCASE wire strings
  (`ScreenQualities`, `ScreenStates`, `ScreenReasons`, `RtcKinds`, `RingActions`, `LocationProviders`);
  `rtc.signal` carries `kind`; `Permissions` gains `LOCATION` and `ACCESSIBILITY`. The phone sends the
  `screen.start.result {audio, control}` before any `screen.state`, and reports only `DENIED`,
  `STOPPED_ON_PHONE`, `STOPPED`, `ERROR`.
- **WebRTC** (`io.github.webrtc-sdk:android`): the phone is the offerer with non-trickle SDP (gather ≤ 4 s).
  `ScreenSession` sits behind the `RtcEngine` seam (tested with a fake); `WebRtcEngine` builds the
  `PeerConnectionFactory`, `ScreenCapturerAndroid`, encoder bitrate per preset, and the pre-negotiated `control`
  data channel (`negotiated=true, id=0`) created before `createOffer`. Audio feeds `AudioPlaybackCapture` into
  `JavaAudioDeviceModule.setAudioBufferCallback` (the hook exists on the 144.x jar); no mic samples are sent.
- **Consent**: `ScreenController` opens `ScreenConsentActivity` directly when the Accessibility service is bound,
  else posts the `screen_requests` notification; it never auto-taps Start. `ScreenCaptureService` (FGS
  `mediaProjection`, plus `microphone` when audio) starts from the consent result, then `onProjectionResult`
  builds the session. A controller `screen.stop` ends the session silently.
- **Control channel**: `ControlMessageParser` validates lowercase-`t` messages (coords 0..1, text ≤ 256, known
  types); `ControlRouter` drops invalid ones and ends the session (`STOPPED`) after 20. The service maps normalized points to pixels
  (with rotation) and dispatches gestures; typing is `ACTION_SET_TEXT` (an empty field showing its hint counts as
  empty: `EditableNode.showingHint`).
- **Session lifecycle** (fixed after the first emulator run, 2026-10-30):
  - `ScreenController.currentId()` is the running session's id, falling back to the pending one; consent clears
    `pending`, so the browser's ANSWER and `screen.stop` must match the running session.
  - `ScreenSession` calls `onEnded` on every end, and the controller stops `ScreenCaptureService` through
    `ScreenConsentFlow.stopCapture()`, so the recording indicator never outlives the session.
  - `GatheringTracker`: WebRTC can report gathering COMPLETE before the first candidate. The offer waits for
    COMPLETE, at least one candidate and 200 ms without a new one (still capped at 4 s).
  - `ScreenRtc` logs gathering, candidate types and connection states, never SDP or addresses.
  - `ControlOutbox`: the peer connection reports CONNECTED before the `control` channel opens, and WebRTC drops
    sends on a channel that isn't open. Phone → browser messages wait in the outbox (max 16) until OPEN.
- **Debug pairing link**: `MainActivity` keeps an unused `dcontrol://pair` link in its saved state
  (`PairingLink`), so a recreation during a slow cold start doesn't fall back to onboarding.
- **Android 17 local network**: `ACCESS_LOCAL_NETWORK` is requested by the pairing route before the preview call
  when `LocalNetwork.isLocal(baseUrl)` (private IPv4/IPv6 literals, `.local`, `.home.arpa`).
- **Find my phone**: `Ringer` (alarm stream to max, auto-stop 5 min, restore), `Locator` (best provider, 10 s
  fresh attempt, then last-known flagged `stale`), `Locker` (`GLOBAL_ACTION_LOCK_SCREEN` + public notification,
  `PERMISSION_REQUIRED {ACCESSIBILITY}` when off). `find.locate` answers `PERMISSION_REQUIRED {LOCATION}`,
  `UNSUPPORTED` (location off) or `NOT_FOUND` (no fix).
- **Permissions / FGS**: `FOREGROUND_SERVICE_MEDIA_PROJECTION` + type `mediaProjection`; `RECORD_AUDIO` +
  `FOREGROUND_SERVICE_MICROPHONE` + type `microphone` (audio only); `BIND_ACCESSIBILITY_SERVICE` on the service;
  `ACCESS_COARSE/FINE_LOCATION` then `ACCESS_BACKGROUND_LOCATION`. No `USE_FULL_SCREEN_INTENT`, device admin or
  `SYSTEM_ALERT_WINDOW`.
- **Deviation**: a pointer gesture is batched and dispatched on release rather than streamed as continued
  strokes, so a drag has no live feedback on a real device. Everything else matches the plan.

## Remote camera (Phase 5)

- **Protocol** (`Phase5Protocol.kt`, `MessageTypes.CAMERA_*`, `Permissions.CAMERA`): `camera.start`,
  `camera.capture`, `camera.switch` (commands), `camera.stop` + `camera.signal` ANSWER (controller → phone),
  `camera.state` + `camera.signal` OFFER (phone → controller). `camera.signal` reuses the `RtcSignal` shape.
  Values are UPPERCASE wire strings (`CameraFacings`, `CameraStates`, `CameraReasons`, `CaptureKinds`).
- **Shared WebRTC**: the screen engine lives in `:core:rtc`; each feature supplies an `RtcSource`. Screen keeps its
  track ids, presets and playback audio. A source without an audio module gets WebRTC's standard mic module.
- **Consent**: without `CAMERA` the phone answers `PERMISSION_REQUIRED {CAMERA}` and shows nothing. Otherwise
  `CameraConsentActivity` opens directly while the app is in the foreground, else the `camera_requests`
  notification opens it. It is never accepted automatically, and it requests no permissions itself; the Camera
  card grants `CAMERA` and `RECORD_AUDIO`. `CameraCaptureService` (FGS `camera`, plus `microphone` with audio)
  shows "Camera shared with …" with Stop.
- **Capture**: `CameraXCapture` binds Preview + ImageCapture + VideoCapture to one lens. If that fails, it binds
  Preview + ImageCapture and reports `video: false`. The preview's `SurfaceRequest` is served by a WebRTC
  `SurfaceTextureHelper`, and rotation comes from `TransformationInfo`. Photos and clips are written to temp
  files, uploaded by `CameraUploader` (`PUT /api/device-api/camera/{mediaId}`), deleted, and then answered.
  Upload refusals map 404 → `NOT_FOUND`, 413 → `PAYLOAD_TOO_LARGE`, 415 → `UNSUPPORTED`.
- **Lifecycle**: the result `{audio}` comes first, and `AWAITING_CONSENT` about 150 ms later. A new start
  replaces the old session. `camera.stop` or losing the controller ends the session silently, and every end stops
  the capture service. After a switch, `LIVE` is sent again with the new `facing`.
- **Emulator run (2026-10-30)**: consent (direct and through the notification), decline, live view, switch,
  photo, clip, and every way of ending a session work. Clip audio there is AMR-NB from the emulator's camcorder
  profiles, which browsers play silent. CameraX 1.6 takes the codec from the profile and has no public override.
- **Not yet checked on a real phone**:
  - AAC clip audio, with live mic audio at the same time (two `AudioRecord`s);
  - the photos-only fallback;
  - front/back rotation.

## Credentials, pairing and connection lifecycle

Unchanged from Phase 1: Keystore AES-GCM `CredentialStore`; QR → preview (host + masked email) → claim;
`ConnectionService` (`connectedDevice`) starts from the UI or `BootReceiver`; `DeviceSocket` hello/ack/heartbeat,
full-jitter `Backoff`, `4001`/`4003`/`401` → Unpaired, `4009` → wait 60 s.

## Tests

| Where | What |
|---|---|
| `core/websocket/src/test` | `BackoffTest`, `ProtocolCodecTest`, `DeviceSocketTest` (+ events only after `hello.ack`, event handlers, typed commands, VALIDATION/PERMISSION_REQUIRED results, `details.permission`), `Phase3ProtocolTest` (DialNumber, cursors, page fill, ms timestamps, error details), `OnlineWatcherTest` |
| `core/network/src/test` | `ApiCallTest` |
| `core/storage/src/test` | `AndroidStorageRootsTest` (Robolectric `StorageVolumeBuilder`) |
| `core/database/src/test` | `DaoTest` (in-memory Room 3) |
| `feature/files/src/test` | `FileSystemTest` (temp dirs: escapes, blocked paths, paging), `FilesCommandsTest`, `TransferEngineTest` (MockWebServer: chunk resume, offset conflict, Range resume, hash mismatch, report-after-reconnect, cancel), `FilesAccessCardTest` |
| `feature/notifications/src/test` | `EventCoalescerTest` (virtual time), `NotificationMapperTest`, `NotificationCommandsTest` (list/dismiss/reply with Robolectric `StatusBarNotification`), `NotificationScreensTest` |
| `feature/clipboard/src/test` | `ClipboardTest` (set, send, limits, card) |
| `feature/deviceinfo/src/test` | `DeviceInfoCollectorTest` |
| `core/telephony/src/test` | `TelephonyCoreTest` (debounced `ProviderChanges` with Robolectric `notifyChange` + virtual time, `ContactNames` cache/permission, `require`) |
| `feature/contacts/src/test` | `ContactsStoreTest` against `FakeContactsProvider` (applies real `ContentProviderOperation`s: paging by byte budget, filter, merge + type mapping, rewrite on the writable raw contact, version assert → CONFLICT, read-only → FORBIDDEN, create with back references, accounts, delete failures), `ContactsCommandsTest` (permissions, photo upload with MockWebServer, cards) |
| `feature/calllog/src/test` | `CallLogTest` (mapping, keyset paging over 1200 rows, type filter, delete limits, `call.place` stripping and MMI/SIM/telephony/permission refusals, sims, card) |
| `feature/sms/src/test` | `SmsTest` against `FakeSmsProvider` (threads + fallback + paging, SMS/MMS merge order and cursor, `sms.send` validation and part limit, status aggregation, watcher received/changed, card with restricted hint) |
| `core/accessibility/src/test` | `AccessibilityBridgeTest` (register/unregister, `available`, stale-service guard) |
| `feature/remotecontrol/src/test` | `GestureStreamerTest` (tap vs long press, swipe, wheel → swipe, rotation mapping), `TextInjectorTest` (fake node: insert/replace/backspace/enter), `RemoteControlCardTest` |
| `feature/screen/src/test` | `ControlMessageParserTest` (limits, unknown types, router drop counter), `ScreenSessionTest` (fake `RtcEngine`: offer after gathering, answer, silent stop, `control.available` change), `ScreenCommandsTest` (consent path with/without Accessibility, audio/control flags), `ScreenShareCardTest` |
| `core/rtc/src/test` | `GatheringTrackerTest`, `ControlOutboxTest` (moved from screen) |
| `feature/camera/src/test` | `CameraSessionTest` (fake `RtcEngine` + `CameraCapture`: offer, LIVE, silent stop, capture and switch through the seam), `CameraCommandsTest` (Robolectric: consent direct vs notification, `CAMERA`/`RECORD_AUDIO` gating, validation, capture only while live, upload errors), `CameraShareCardTest` |
| `feature/findphone/src/test` | `RingerTest` (max volume, auto-stop, restore), `LocatorTest` (Robolectric `ShadowLocationManager`: disabled, none, field mapping), `LockerTest` (PERMISSION_REQUIRED vs lock + notify), `FindCommandsTest` (ring/lock/locate validation and permission errors), `FindPhoneCardTest` |
| `feature/pairing`, `feature/connection` | Phase 1 tests |
