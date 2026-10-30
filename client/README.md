# dcontrol client (Android)

The Android app that runs on the phone. It pairs with the controller by scanning a QR code, then keeps an
outbound WebSocket open from a foreground service so the controller can reach the phone behind NAT.
The wire contract is [`controller/docs/protocol/ws-v1.md`](../controller/docs/protocol/ws-v1.md), plus
[`transfers-v1.md`](../controller/docs/protocol/transfers-v1.md) for file contents over HTTP.

## Requirements

- JDK 17 or newer (the build targets Java 17 bytecode). Use Android Studio's bundled JBR
  (`<Android Studio>/jbr`) as `JAVA_HOME`. GraalVM's `jlink` fails AGP's `JdkImageTransform` on
  `android-37/core-for-system-modules.jar`. To keep your shell's JDK, set
  `org.gradle.java.home=<path to jbr>` in `~/.gradle/gradle.properties` instead.
- Android SDK with platform 37 (Android Studio installs it). Point Gradle at it with `ANDROID_HOME`
  or a `local.properties` file. Escape the drive colon and use forward slashes:
  `sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk`
- Robolectric tests on JDK 25 need extra `--add-opens`/`--add-exports` flags; the convention plugins
  already pass them (`AndroidConfig.UNIT_TEST_JVM_ARGS`).
- A running controller (see `controller/README.md`)

## Build and test

```bash
./gradlew testDebugUnitTest
```

```bash
./gradlew lintDebug assembleDebug
```

```bash
./gradlew installDebug
```

`:core:websocket` is a plain Kotlin/JVM module, so its tests run without the Android SDK:

```bash
./gradlew :core:websocket:test
```

Compose UI tests run on the JVM with Robolectric; no emulator is needed for `testDebugUnitTest`.

## Pairing with a local controller

The QR code contains the controller's public base URL (`dcontrol.public-base-url` on the controller).
The phone has to be able to reach that address:

| Phone | Controller URL to configure |
|---|---|
| Android emulator | `http://10.0.2.2:8080` (the emulator's alias for the host machine) |
| Real phone on the same Wi-Fi | `http://<your PC's LAN IP>:8080` |
| Production | `https://…` (release builds refuse cleartext HTTP) |

Emulators can't easily scan a QR code, so **debug builds** accept the QR payload through a deep link.
Copy the payload shown under the QR code in the web UI and run:

```bash
adb shell 'am start -a android.intent.action.VIEW -d "dcontrol://pair?v=1&u=http%3A%2F%2F10.0.2.2%3A8080&t=<token>"'
```

Keep the single quotes around the `am start` command: without them the device shell treats `&` as a
command separator. The app still shows the confirmation screen (controller host + account) before pairing.
Release builds don't declare the deep link.

Useful emulator commands while testing the connection:

```bash
adb shell dumpsys battery set level 42
```

```bash
adb shell dumpsys battery reset
```

## Permissions (Phase 1)

| Permission / type | Kind | How it is granted | Why |
|---|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | normal | at install | REST calls, WebSocket, reconnect when the network returns |
| `CAMERA` | runtime | asked on the scan screen after a rationale; "Open settings" if permanently denied | Reading the pairing QR code |
| `ACCESS_LOCAL_NETWORK` (Android 17+) | runtime (Nearby devices) | asked right after scanning a code whose controller has a private address (LAN, `10.0.2.2` on the emulator, `.local`). If denied, pairing shows how to allow it | Android 17 drops connections to private addresses without it, which looked like "Can't reach the controller". The grant also covers the device socket |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | normal | at install | `ConnectionService` runs with `foregroundServiceType="connectedDevice"` |
| `CHANGE_NETWORK_STATE` | normal | at install | Prerequisite for the `connectedDevice` type on API 34+ |
| `POST_NOTIFICATIONS` (API 33+) | runtime | asked on the onboarding screen | Shows the connection notification. The service still runs if denied |
| `RECEIVE_BOOT_COMPLETED` | normal | at install | Restart the connection after reboot / app update (`connectedDevice` may start from boot on Android 15+) |
| Battery-optimization exemption | — | "Open settings" button on the status screen (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`) | Keeps aggressive OEM task killers away. The app never uses `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |

Play Store note: reviewers may question `connectedDevice` for a browser-remote use case. The fallback is
`specialUse` with a written justification.

## Permissions (Phase 2)

No new foreground-service type: file transfers run inside the existing `connectedDevice` `ConnectionService`,
so they only progress while it runs.

| Feature | Permission / mechanism | How it is granted | Notes |
|---|---|---|---|
| Files, API 30+ | `MANAGE_EXTERNAL_STORAGE` ("All files access") | special app access: "Allow access" on the status screen opens `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` | **Google Play:** needs the "All files access" declaration form; it is allowed when file management is a core feature. `Android/data` and `Android/obb` stay off-limits and answer `FORBIDDEN` |
| Files, API 26–29 | `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=29`), `requestLegacyExternalStorage` for API 29 | runtime, from the same card; "Open settings" after a permanent denial | |
| Notifications | `MirrorListenerService` declared with `BIND_NOTIFICATION_LISTENER_SERVICE` | the user enables **Notification access** ("Open settings" opens the per-app page on API 30+) | Bound by the system, not a foreground service. A sideloaded APK on Android 13+ may show the switch greyed out: App info → ⋮ → **Allow restricted settings**, then try again. Android 15+ hides sensitive notifications (one-time codes) from third-party listeners, so they arrive redacted. Reply uses the posting app's own `PendingIntent`; no permission. App names and icons come from the notification itself, so `QUERY_ALL_PACKAGES` isn't needed |
| Transfer progress | `POST_NOTIFICATIONS` | already asked during onboarding | Without it transfers still run, just without a progress notification |
| Clipboard | none | — | Android 10+ only lets the app in front read the clipboard, so phone → web only happens on a user action: the "Send clipboard" button on the status screen, the Quick Settings tile, or the action on the connection notification. Android 12+ shows a "pasted" toast. Web → phone works in the background; Android 13+ shows the system copy confirmation |
| Quick Settings tile | `ClipboardTileService` declared with `BIND_QUICK_SETTINGS_TILE` | the user adds the "Send clipboard" tile | On API 34+ it opens the activity through `startActivityAndCollapse(PendingIntent)` |
| Device info | `ACCESS_NETWORK_STATE` (already normal, Phase 1) | at install | No Wi-Fi SSID (would need location) and no IMEI/serial (not available to apps) |

Testing notifications on an emulator:

```bash
adb shell cmd notification post -S bigtext -t "Alice" tag1 "See you at 6?"
```

## Permissions (Phase 3)

Contacts, call log and SMS are read live and never stored on the phone or the controller. No new
foreground-service type: commands and provider observers run inside the existing `connectedDevice`
`ConnectionService`, and the observers only run while the phone is online. Each feature has a card on the
status screen. The card asks for its runtime permissions together, and once Android stops showing the
dialog it changes to "Open app settings".

| Feature | Permission | How it is granted | Notes |
|---|---|---|---|
| Contacts read | `READ_CONTACTS` | runtime (Contacts card) | Also names SMS participants |
| Contacts create / edit / delete | `WRITE_CONTACTS` | runtime, same group as read | Deleting a synced contact also deletes it from its account (e.g. Google). Contacts of read-only accounts answer `FORBIDDEN` |
| Call log read | `READ_CALL_LOG` | runtime (Calls card) | **Google Play:** restricted, needs the SMS/Call Log permissions declaration ("cross-device synchronization" is a listed use; approval is at Google's discretion) |
| Call log delete | `WRITE_CALL_LOG` | runtime (Calls card) | Same Play restriction |
| Place a call | `CALL_PHONE` | runtime (Calls card) | `TelecomManager.placeCall` from the background service; Telecom shows its own in-call UI. Only `+` and digits are dialled, so MMI/USSD codes (`*#06#`, `**21*…#`) are refused |
| SIM picker and SIM labels | `READ_PHONE_STATE` | runtime (Calls card) | Optional. Without it, `telephony.sims` answers `access: MISSING` and the default SIM is used |
| SMS read | `READ_SMS` | runtime (Messages card) | Play-restricted like the call log. **Android 15+, sideloaded APK:** SMS permissions are restricted settings. If the dialog doesn't appear or the switch is greyed out, open App info → ⋮ → **Allow restricted settings** first; the card shows this hint |
| SMS send | `SEND_SMS` | runtime (Messages card) | Same restrictions. Carrier charges apply. Sent messages are written to the provider by the system (non-default SMS app). Sent/delivered reports go to an unexported `SmsStatusReceiver` |
| Live updates | none | — | `ContentObserver`s on the SMS, MMS, call log and contacts providers, debounced to 1 s. There is deliberately **no `RECEIVE_SMS`** |

The app never becomes the default SMS app. As a result:
- it can't mark messages as read, delete SMS or send MMS;
- MMS show up as a placeholder ("MMS · subject");
- it declares `android.hardware.telephony` as not required, so on a device without telephony, calls and SMS
  answer `UNSUPPORTED`.

Testing on an emulator:

```bash
adb emu sms send 5551234 "See you at 6?"
```

```bash
adb emu gsm call 5551234
```

## Screen mirroring, remote control, find my phone (Phase 4)

Video, audio and input travel peer to peer over WebRTC (or the coturn TURN relay); the controller only relays
signaling and never sees the media. Signaling is non-trickle: each side gathers ICE (capped at 4 s) and sends
one SDP. The phone is always the offerer, and the browser's answer comes back over `rtc.signal`.

- **Consent every session (Android 14+).** Each MediaProjection token is single-use, so the user taps **Start**
  every time; dcontrol never auto-taps it. With the Accessibility service bound, the consent screen opens
  directly (background-activity-launch exemption); otherwise a high-importance `screen_requests` notification
  asks the user to tap.
- **Capture order (Android 14+).** `ScreenCaptureService` (FGS type `mediaProjection`) starts from the consent
  result, *before* `getMediaProjection()`.
- **Audio** is the phone's own playback (`AudioPlaybackCapture`, USAGE media/game/unknown) fed into WebRTC's
  `JavaAudioDeviceModule.setAudioBufferCallback`; no microphone samples are sent. WebRTC still opens an
  `AudioRecord`, so the system mic indicator shows while audio is on. FGS type `microphone` is added only then.
- **Remote input** goes over a pre-negotiated `control` data channel (`negotiated=true, id=0`). Coordinates are
  0..1 fractions of the frame; the Accessibility service maps them to pixels (with rotation) and dispatches
  gestures. Typing is `ACTION_SET_TEXT`, not key events, so IME-only or custom editors may ignore it. Invalid
  channel messages are dropped and, after 20, the session is torn down.
- **Find my phone:** ring on the alarm stream at full volume (auto-stop after 5 min), locate with the platform
  location provider (10 s fresh attempt, then the last known fix, flagged `stale`), and lock via the
  Accessibility `GLOBAL_ACTION_LOCK_SCREEN` plus a public lock-screen notification.

### Permissions and foreground-service types (Phase 4)

| Feature | Permission / component | Kind |
|---|---|---|
| Mirroring | `FOREGROUND_SERVICE_MEDIA_PROJECTION` + `ScreenCaptureService` type `mediaProjection` | normal + FGS type |
| Phone audio | `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, FGS type `microphone` (only when audio is on) | runtime + FGS type |
| Remote control / lock | `ControlAccessibilityService` guarded by `BIND_ACCESSIBILITY_SERVICE` | enabled in Settings › Accessibility |
| Locate | `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, then `ACCESS_BACKGROUND_LOCATION` | runtime, two steps |
| Ring | none (alarm stream) | — |
| Consent prompt | `POST_NOTIFICATIONS` (Phase 1) | runtime; new `screen_requests` channel |

No `USE_FULL_SCREEN_INTENT`, no device admin, no `SYSTEM_ALERT_WINDOW`. Sideloaded on Android 13+, the user must
"Allow restricted settings" before enabling the Accessibility service. The debug APK grows ~15–20 MB from the
native WebRTC libraries.

**Known limits:** someone must tap Start each session; secure (`FLAG_SECURE`) windows are black and Android 15+
hides sensitive notifications while sharing; one viewer per phone; typing is set-text, not key events. A real
device streams continued strokes for live drag feedback — this build batches a pointer gesture on release.

## Remote camera (Phase 5)

The web Camera tab shows a live view from the phone's camera and can take photos and short clips. The live view
uses the same WebRTC path as screen mirroring (`:core:rtc`, non-trickle, phone offers), signaled over
`camera.signal` and without a data channel. Because `CAMERA` has no per-capture system dialog, dcontrol's own
prompt is the gate, and the feature follows the screen-sharing transparency rules:

- **Consent every session, never auto-accepted.** `camera.start` asks "Share your camera with <viewer>?" in
  `CameraConsentActivity`. It opens directly when the app is visible; otherwise the high-importance
  `camera_requests` notification ("Tap to share your camera") opens it. Dismissing or leaving the prompt
  declines (`DENIED`). Nothing touches the camera before the owner taps **Share camera**.
- **Grants first.** Without `CAMERA` the phone answers `PERMISSION_REQUIRED` (`details.permission = CAMERA`) and
  shows nothing; the Camera card on the status screen grants it. Audio is offered only when `RECORD_AUDIO` is
  granted (`camera.start.result {audio}`).
- **Visible while it runs.** `CameraCaptureService` (FGS type `camera`, plus `microphone` with audio) starts from
  the approval, while the app is in front as Android 14+ requires. Its ongoing "Camera shared with <viewer>"
  notification carries **Stop**; the system camera/mic indicators stay lit. Stop, the browser closing the tab
  (`camera.stop`), or the phone going offline ends the session.
- **One camera, three uses.** `CameraXCapture` binds Preview + ImageCapture + VideoCapture on one
  `ProcessCameraProvider`. The Preview renders into the WebRTC `SurfaceTextureHelper` (the CameraX → WebRTC
  bridge in `CameraXVideoCapturer`), so a lens switch rebinds CameraX while the stream stays on the same track.
  Devices that can't bind all three fall back to Preview + ImageCapture and report `video: false`.
- **Captures** are a full-resolution JPEG or an MP4 (H.264/AAC, up to 60 s) written to the cache directory,
  uploaded with `PUT /api/device-api/camera/{mediaId}` (`Authorization: Device …`), deleted, and only then
  answered with `{width, height, durationMs}`. One capture or switch runs at a time (`CONFLICT` otherwise).

| Feature | Permission / component | Kind |
|---|---|---|
| Live view, photos, clips | `CAMERA` | runtime (Camera card) |
| Clip and live audio | `RECORD_AUDIO` (optional) | runtime (Camera card) |
| Capture service | `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE`, `CameraCaptureService` type `camera` (+ `microphone`) | normal + FGS type |
| Consent prompt | `POST_NOTIFICATIONS` (Phase 1) | runtime; new `camera_requests` channel |

**Known limits:** the owner must approve each session on the phone; live audio uses WebRTC's microphone module
while a clip with sound opens a second `AudioRecord`, so on some devices one of them may get silence (to be
checked on hardware); the live view is not mirrored for the front camera.

## Remote microphone (Phase 6)

The web Microphone tab listens to the phone's microphone live and can record short audio clips. It is the camera
session (Phase 5) without video: the phone offers one audio m-line over `mic.signal`, with no data channel. Consent
is identical to the camera.

- **Consent every session, never auto-accepted.** `mic.start` asks "Share your microphone with <viewer>?" in
  `MicConsentActivity`, opened directly when the app is visible or via the high-importance `mic_requests`
  notification otherwise. Dismissing or leaving the prompt declines (`DENIED`). Nothing touches the microphone
  before the owner taps **Share microphone**.
- **The grant.** `RECORD_AUDIO` is required; without it `mic.start` answers `PERMISSION_REQUIRED` (`MICROPHONE`)
  and the phone shows nothing. The Microphone card on the status screen grants it.
- **Visible while it runs.** `MicCaptureService` (FGS type `microphone`) starts from the approval; its ongoing
  "Microphone shared with <viewer>" notification carries **Stop** and the system mic indicator stays lit. Stop,
  the browser closing the tab (`mic.stop`), or the phone going offline ends the session.
- **One microphone consumer.** Clips are encoded from the same samples WebRTC captures: a
  `JavaAudioDeviceModule` samples-ready callback feeds `AacClipRecorder` (PCM → MediaCodec AAC-LC 64 kbps →
  MediaMuxer `.m4a`), so live audio keeps playing while a clip records. Presentation times and the clip's end come
  from the sample count (`ClipTiming`), not the wall clock. The clip is uploaded with
  `PUT /api/device-api/mic/{mediaId}` (`audio/mp4`), deleted, and only then `mic.capture` is answered. One clip
  records at a time (`CONFLICT` otherwise).
- **The microphone has one user** (`MicrophoneLock`, in `:core:rtc`). `mic.start` fails with `CONFLICT` while a
  camera or screen session holds the microphone; a `camera.start`/`screen.start` with audio while the microphone
  session runs starts without audio (`{audio:false}`).

| Feature | Permission / component | Kind |
|---|---|---|
| Live audio, clips | `RECORD_AUDIO` | runtime (Microphone card) |
| Capture service | `FOREGROUND_SERVICE_MICROPHONE`, `MicCaptureService` type `microphone` | normal + FGS type |
| Consent prompt | `POST_NOTIFICATIONS` (Phase 1) | runtime; new `mic_requests` channel |

**Known limits:** the AAC encoder and the audio-only WebRTC offer are checked on the emulator, not in unit tests;
the owner must approve each session on the phone.

## Modules

| Module | Contents |
|---|---|
| `:app` | `Application`, `MainActivity`, navigation, onboarding, debug deep link, network security config |
| `:core:websocket` | Pure Kotlin/JVM: protocol models + codec, `Backoff`, `DeviceSocket` (OkHttp), `ConnectionState` |
| `:core:network` | OkHttp/Retrofit setup, problem-details errors, Keystore-encrypted `CredentialStore`, `DeviceInfoProvider` |
| `:core:designsystem` | Material 3 theme and shared components |
| `:core:permissions` | Runtime-permission helpers, rationale UI, settings shortcuts |
| `:feature:pairing` | QR scan (CameraX + ML Kit), confirm screen, preview/claim |
| `:feature:connection` | `ConnectionService`, boot receiver, battery/network sources, status screen |
| `:core:storage` | Storage volume roots and the All-files-access check |
| `:core:database` | Room 3 database: pending transfers, mirrored-app toggles |
| `:feature:files` | `files.*` commands, root-confined `FileSystem`, resumable `TransferEngine`, Files access card |
| `:feature:notifications` | `NotificationListenerService`, live mirroring, dismiss and reply, icon upload, mirrored-apps screen |
| `:feature:clipboard` | `clipboard.set`, "Send clipboard" (button, tile, notification action) |
| `:feature:deviceinfo` | `device.info` snapshot |
| `:core:telephony` | Permission checks, `ContentQueries` seam over `ContentResolver`, debounced `ProviderChanges`, `ContactNames` (PhoneLookup), `SimCards` |
| `:feature:contacts` | `contacts.*` commands (`ContactsStore`), photo upload to the blob relay, `contacts.changed` watcher, Contacts card |
| `:feature:calllog` | `calls.*`, `call.place`, `telephony.sims`, `calls.changed` watcher, Calls card |
| `:feature:sms` | `sms.threads` / `sms.messages` (`SmsStore`), `sms.send` with sent/delivered tracking, `sms.received` / `sms.changed` watcher, Messages card |
| `:core:accessibility` | `AccessibilityBridge` (the service ⇄ feature seam), `ControlActions`, accessibility-settings helpers. Features depend on this, never on each other |
| `:feature:remotecontrol` | `ControlAccessibilityService`, `GestureStreamer` (normalized events → gesture paths), `TextInjector` (`ACTION_SET_TEXT`), Remote control card |
| `:core:rtc` | The shared WebRTC plumbing: `RtcEngine` / `RtcEngineFactory` seam, `RtcSource` (video optional), `WebRtcEngine`, `GatheringTracker`, `ControlOutbox`, `MicrophoneLock` (one microphone user) |
| `:feature:screen` | `screen.*` / `rtc.signal`, `ScreenConsentActivity`, `ScreenCaptureService` (FGS mediaProjection + microphone), `ScreenSession`, `ScreenRtcSource` (MediaProjection + playback audio), the `control` data channel, Screen share card |
| `:feature:camera` | `camera.*`, `CameraConsentActivity`, `CameraCaptureService` (FGS camera + microphone), `CameraSession` behind the `CameraCapture` seam, `CameraXCapture` + the CameraX → WebRTC bridge, `CameraUploader`, Camera card |
| `:feature:mic` | `mic.*`, `MicConsentActivity`, `MicCaptureService` (FGS microphone), `MicSession` behind the `MicCapture` seam, `MicSource` + `AacClipRecorder` (`ClipTiming`), `MicUploader`, Microphone card |
| `:feature:findphone` | `find.ring` / `find.lock` / `find.locate`, `Ringer`, `Locator` (`LocationManagerCompat`), `Locker` (lock + public notification), Find my phone card |

Build logic lives in `build-logic/` (convention plugins `dcontrol.android.*`, `dcontrol.jvm.library`), and
versions in `gradle/libs.versions.toml`.
