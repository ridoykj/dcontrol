# Phase 6: consent-gated remote microphone

Status: implemented, verified by the build gates and an emulator end-to-end run, and confirmed (2026-10-30). Not
tried on a real phone. Not committed.

## Verification

| Check | Result |
|---|---|
| `controller`: `./mvnw verify` | 163 JUnit (9 new: `MicSessionIntegrationTest`, `MicCaptureIntegrationTest`; camera tests unchanged after the `CaptureMediaStore` refactor) + 106 Vitest (11 new), frontend build: BUILD SUCCESS |
| `client`: `./gradlew testDebugUnitTest lintDebug assembleDebug` | 270 unit tests (41 new: `:feature:mic` 35, `MicrophoneLockTest` 3, camera 2, screen 1), lint clean, APK built |
| Emulator end to end | Run 2026-10-30 on `Medium_Phone_API_37.0` (Android 17) with host audio input on. Results below |

### Emulator run

| Check | Result |
|---|---|
| Consent, app in front | "Share your microphone with Demo User?" opens directly |
| Consent, app in background | High-importance "Tap to share your microphone" notification (`mic_requests`); tapping it opens the same prompt |
| Decline | The web shows "Microphone access was declined on the phone"; no service starts |
| While live | FGS type microphone (`0x80`), "Microphone is being shared · Shared with Demo User" with Stop, green mic indicator. One `AudioRecord` (`VOICE_COMMUNICATION`) |
| Live audio | One audio track, no video, through TURN, ~15–25 kbit/s. The emulator's mic delivered near-silence (peak ≈ 3·10⁻⁴), so the level meter barely moved; real sound was not heard |
| Clip | 10 s clip while live audio kept playing, still one `AudioRecord`. MPEG-4 (`mp42`) with an `mp4a`/`esds` sound track, mono, ~52 kbit/s; the browser plays it (10.4 s) and offers Download |
| Microphone owner | Mic live → camera with audio starts video only (FGS `0x40`) and the mic keeps running; camera with audio (`0xC0`) → `mic.start` shows "The microphone is in use by the camera or screen session" |
| Ending | Stop on the web, Stop in the phone's notification, closing the tab, and the controller going away each stop the service and the `AudioRecord`; the web says why |
| No `RECORD_AUDIO` | The web shows the Microphone permission card; the phone shows nothing |

### Fixed after the emulator run

| Bug | Fix |
|---|---|
| The camera page blamed a missing permission when it got no audio because a microphone session held the mic | The message now names both causes (`CameraPage.tsx`) |

### Known limit found on the emulator

The emulator's audio input runs at 8 kHz (`AudioIn` thread), so its clips are 8 kHz AAC. The recorder follows the
source rate; phones capture at 16–48 kHz.

### Still to check on a real phone

- Audible live audio and clips with real sound, and the level meter.
- The capture sample rate (expected 16–48 kHz) and clip quality.
- Echo cancellation and noise suppression on far-field sound.

## Scope

Live listening to the phone's microphone and short audio clips, each session accepted by the phone owner. See
[microphone.md](../features/microphone.md) and
[ws-v1.md](../protocol/ws-v1.md#phase-6-additions-still-v1-all-additive).

## Decisions

| Topic | Decision |
|---|---|
| Consent | The camera's contract: the app's own prompt on every session, never automatic; an FGS of type `microphone` with an ongoing notification and Stop. Missing `RECORD_AUDIO` → `PERMISSION_REQUIRED` (`MICROPHONE`), nothing shown on the phone |
| Live audio | WebRTC through the shared `:core:rtc` engine, which now skips the video track for a source with `hasVideo = false`. No data channel |
| Clips | Encoded on the phone from the samples WebRTC already captures (`JavaAudioDeviceModule` samples callback → AAC-LC → MPEG-4), so the microphone has one reader and live audio continues while a clip records |
| Microphone owner | `MicrophoneLock` on the phone: a microphone session refuses to start (`CONFLICT`) while camera or screen audio runs; camera or screen started during a microphone session runs without audio |
| Storage | The camera's spool, generalized as `media.CaptureMediaStore`: single-use slots, `ftyp` + `mvhd` checks, `<storage.dir>/mic`, 30 min, at most 20 per phone, deleted on unpair and restart. No migration |
| Limits | Clips 1 s – 5 min, 20 MiB; `mic.start` 6/min, `mic.capture` 20/min per phone, both audited; capture timeout 6 min |
