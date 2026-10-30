# Phase 5: consent-gated remote camera

Status: implemented, verified by the build gates and an emulator end-to-end run, and confirmed (2026-10-30).
Not tried on a real phone. Not committed.

## Verification

| Check | Result |
|---|---|
| `controller`: `./mvnw verify` | 154 JUnit (15 new: `CameraSessionIntegrationTest`, `CameraCaptureIntegrationTest`, `Mp4DurationTest`) + 95 Vitest (12 new), frontend build: BUILD SUCCESS |
| `client`: `./gradlew testDebugUnitTest lintDebug assembleDebug` | 229 unit tests (35 in `:feature:camera`, 7 moved to `:core:rtc`, screen's 19 still green), lint clean, APK built |
| Emulator end to end | Run 2026-10-30 on `Medium_Phone_API_37.0` (Android 17), back = virtual scene, front = emulated. Results below |

### Emulator run

| Check | Result |
|---|---|
| Consent, app in front | The prompt "Share your camera with Demo User?" opens directly |
| Consent, app in background | High-importance "Tap to share your camera" notification; tapping it opens the same prompt |
| Decline | The web shows "Camera access was declined on the phone"; no service starts |
| While live | FGS types camera + microphone (`0xC0`), "Camera is being shared · Shared with Demo User · with microphone" with Stop, green camera and mic indicator |
| Live view | Video + audio tracks, up to 30 fps through TURN. The virtual scene starts at 2–3 fps while it warms up |
| Switch | Front ↔ back on the same track |
| Photo | 1440×1920 JPEG through the upload slot, shown in the strip with Download |
| Clip | 10 s MP4 (H.264 + sound track), plays in the strip after the CSP fix below |
| Ending | Stop in the notification, closing the tab, and the phone going offline (controller restart, app reinstall) each stop the service and release the camera; the web says why |
| No `CAMERA` grant | The web shows the Camera permission card; the phone shows nothing; its Remote camera card offers Grant |
| Restart | Controller restart deletes the spooled captures |

### Fixed after the emulator run

| Bug | Fix |
|---|---|
| Clips didn't play: the CSP had no `media-src`, so `blob:` video was blocked | `media-src 'self' blob:` in `SecurityConfig`; asserted in `CameraCaptureIntegrationTest` |

### Known limit found on the emulator

Clip audio was AMR-NB, which browsers can't play. The emulator's camcorder profiles record AMR, and CameraX 1.6
takes the audio codec from the profile, with no public setting to override it. Codec capabilities as the source
(tried) don't change the audio. Real phones' HD profiles record AAC. Check it on a real phone.

### Still to check on a real phone

- Clip audio is AAC, and live audio and a clip's audio work together (two `AudioRecord`s).
- Preview + ImageCapture + VideoCapture on one lens, and the photos-only fallback.
- Rotation of the live view for both lenses.

## Scope

A live viewfinder, full-resolution photos, and video clips with microphone audio, from the front or back camera,
each session accepted by the phone owner. See [camera.md](../features/camera.md) and
[ws-v1.md](../protocol/ws-v1.md#phase-5-additions-still-v1-all-additive).

## Decisions

| Topic | Decision |
|---|---|
| Consent | The app's own prompt on every session, never automatic; an FGS of type `camera` (+ `microphone`) with an ongoing notification and Stop. Missing `CAMERA` → `PERMISSION_REQUIRED`, nothing shown on the phone |
| Live view | WebRTC as in Phase 4, sharing one engine: the screen engine moved to `:core:rtc` behind an `RtcSource` seam. No data channel |
| Captures | CameraX `ImageCapture` / `VideoCapture` bound with the preview; uploaded over HTTP to a single-use slot (`PUT /api/device-api/camera/{mediaId}`) before the phone answers `camera.capture` |
| Storage | Disk spool `<storage.dir>/camera` with an in-memory index: 30 min, at most 20 per phone, deleted on unpair and on restart. No migration |
| Validation | Type from magic bytes, clip length from the MP4 `mvhd` box, size caps (20 MiB / 200 MiB); the phone's claims are never trusted |
| Timeouts | `camera.capture` waits up to `dcontrol.camera.capture-timeout` (2 min) instead of the 15 s command timeout (`DeviceCommandRelay.send` with a timeout) |
| Downloads | The browser already holds a capture as a blob, so it saves from that; no download ticket |
| Guardrails | `camera.start` 6/min, `camera.capture` 20/min per phone, both audited |
