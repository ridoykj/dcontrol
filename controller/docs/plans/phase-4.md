# Phase 4: screen mirroring, remote control, find my phone

Status: implemented and verified, confirmed 2026-10-30. Not committed.

## Verification

| Check | Result |
|---|---|
| `controller`: `./mvnw verify` | 139 JUnit (Testcontainers, `SchemaConventionIntegrationTest` with V6) + 83 Vitest, frontend build: BUILD SUCCESS |
| `client`: `./gradlew testDebugUnitTest lintDebug assembleDebug` | 179 unit tests (55 new), lint clean, `app-debug.apk` about 90 MB with the native WebRTC libraries |
| Emulator end to end | Run 2026-10-30 on `Medium_Phone_API_37.0` (Android 17): pairing, overview, files, screen mirroring (direct connection), Home/tap/typing over the data channel, ring, locate, lock. It found the bugs below, all fixed with tests |

### Fixed after the emulator run

| Bug | Fix |
|---|---|
| coturn 4.18 exits at start (`--no-cli`, `--no-dtls` were removed) | Flags dropped from `compose.yaml`; dev adds `--allow-loopback-peers` (both relays are on 127.0.0.1) |
| Blank web UI: the SPA fallback answered `/assets/*.js` with `index.html` | `SpaForwardingController` excludes `assets`; `SpaForwardingIntegrationTest` |
| Android 17 drops connections to `10.0.2.2` and LAN controllers ("Can't reach the controller") | `ACCESS_LOCAL_NETWORK` declared and requested before pairing with a private-address controller |
| The phone ignored the browser's ANSWER and `screen.stop` once consent was given | `ScreenController.currentId()` uses the running session |
| Offer sent without ICE candidates (WebRTC reported COMPLETE too early) | `GatheringTracker` waits for a candidate and a quiet period |
| Capture service and recording indicator stayed on after the web stopped a session | Every session end stops `ScreenCaptureService` |
| Typing into an empty field kept its hint ("Search…dcontrol") | `TextInjector` ignores hint text |
| Buttons with an icon and a text label were squashed to 32 px (`:only-child` ignores text nodes in the vendored Button) | Labels wrapped in `<span>` at 20 call sites; `shadcn/Button.tsx` untouched |
| A pairing link that cold-starts the app opened onboarding when the activity was recreated before the link was used (for example a configuration change while the credentials load) | `PairingLink` keeps the unused link in the saved state; `PairingLinkTest` |
| The phone's `size` and `control` messages were sent at CONNECTED, before the data channel opened, and WebRTC dropped them | `ControlOutbox` holds them until the channel is open; `ControlOutboxTest` |

## Decisions

| Topic | Decision |
|---|---|
| Android WebRTC | `io.github.webrtc-sdk:android:144.7559.15` (LiveKit-maintained, M144). The browser uses its native `RTCPeerConnection` |
| NAT traversal | `coturn/coturn:4.18.0-alpine` in `compose.yaml`; the controller issues coturn REST credentials (12 h) per socket; `{host}` in ICE URLs is the host each socket connected to |
| Signaling | Over the existing sockets, non-trickle (one offer from the phone, one answer from the browser), routed to the one browser socket that started the session |
| Remote control | Touch, gestures, nav keys and typing (`ACTION_SET_TEXT`) through an Accessibility service, over a pre-negotiated data channel `control` |
| Audio | Phone media audio via `AudioPlaybackCapture`; calls are never captured |
| Consent | Android 14+ consent for every session; never auto-clicked |
| Find my phone | Ring (alarm stream), Locate (platform `LocationManagerCompat`, no Play services), Lock + message (Accessibility global action, no device admin) |
| Location | Last fix stored per device in `T_DEVICE_LOCATION` (V6), not audited, physically deleted on "Forget" and unpair. No map: coordinates and links to OpenStreetMap / Google Maps |
| Guardrails | `screen.start` 6/min, `find.ring` 10/min, `find.lock` 5/min per phone, all audited |

## What was added

- **Protocol (additive, still v1):**
  - `screen.start`, `screen.state`, `screen.stop`, `rtc.signal`;
  - `find.ring`, `find.lock`, `find.locate`;
  - `PERMISSION_REQUIRED` values `LOCATION` and `ACCESSIBILITY`;
  - the data channel messages.

  See [`ws-v1.md`](../protocol/ws-v1.md#phase-4-additions-still-v1-all-additive).
- **Controller:**
  - packages `webrtc`, `screen`, `findphone`;
  - `BrowserMessageHandler` (+ `ClosedListener`);
  - `DeviceConnectionListener.onDeviceDisconnected`;
  - `WebSessionRegistry.send` to one socket;
  - `DeviceCommandRelay.ownsActive`;
  - REST `/api/devices/{id}/location` (`GET`, `POST /refresh`, `DELETE`).
- **Web:**
  - Screen tab (`useScreenSession`, `ScreenViewer` with pointer, wheel and keyboard mapping, `NavBar`, stats);
  - Find tab (ring, location card with map links, lock form);
  - `RealtimeClient.send`;
  - `PermissionRequired` for `LOCATION` and `ACCESSIBILITY` (also from REST problem details).
- **Android:**
  - `:core:accessibility` (`AccessibilityBridge`, `ControlActions`);
  - `:feature:remotecontrol` (`ControlAccessibilityService`, `GestureStreamer`, `TextInjector`);
  - `:feature:screen` (`ScreenController`, `ScreenSession` behind the `RtcEngine` seam, `ScreenConsentActivity`,
    `ScreenCaptureService`). Phone audio uses `JavaAudioDeviceModule.setAudioBufferCallback`, so no custom audio
    module was needed;
  - `:feature:findphone` (`Ringer`, `Locator`, `Locker`);
  - the three cards in `featureCards`, and `Phase4Protocol.kt`.
- **Feature docs:** [screen mirroring](../features/screen-mirroring.md), [remote control](../features/remote-control.md),
  [find my phone](../features/find-phone.md).

## Known limits

- Someone must tap Start on the phone for every mirroring session (Android 14+); no unattended access.
- Typing is set-text, not real key events; games and custom editors may ignore it.
- Secure windows are black; Android 15+ hides sensitive notifications while sharing.
- One viewer per phone. No recording.
- Sessions, rate limits and TURN credentials are in memory (single controller instance).
- Location is the last fix only, with no history. Locate needs the phone online.

## Changes from the plan

- A gesture is sent to the phone as one `GestureDescription` when the pointer is released. Taps, long presses,
  swipes and scrolling work, but a drag isn't seen on the phone until it ends.
- After 20 invalid control messages the phone ends the whole session (`STOPPED`), not just the data channel.
- `LocatorTest` covers the fix mapping. Robolectric returns the last known fix from `getCurrentLocation`, so the
  timeout-to-stale path is not covered by a test.
- The phone sends `AWAITING_CONSENT` 150 ms after it accepts `screen.start`, so that the result arrives first.
  The web also buffers early messages, so a late result doesn't lose state.
