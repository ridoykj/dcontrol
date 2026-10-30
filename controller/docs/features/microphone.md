# Microphone

The Microphone tab lets the browser listen to the phone's microphone live and record short audio clips. The phone
owner has to accept every session on the phone. Live audio goes peer to peer over WebRTC, or through coturn, like
the [camera](camera.md) without video. Clips are uploaded to the controller, which keeps them for a short time.

## Consent and visibility

The microphone has no system dialog for each use, so dcontrol's own prompt is the only thing between remote
listening and covert eavesdropping. The feature refuses to work without it:

- **Every session needs the owner's yes on the phone:** "Share your microphone with <viewer>?". It is never accepted
  automatically. Without an answer within 60 s, the controller ends the session (`TIMEOUT`).
- **While it runs**, the phone keeps a foreground service of type `microphone` with an ongoing "Microphone shared
  with <viewer>" notification and a **Stop** action. Android's microphone indicator stays on the whole time.
- **The session ends** when the owner taps Stop, the browser tab stops or closes, or the phone goes offline.
- **Without `RECORD_AUDIO`** the phone shows nothing and answers `PERMISSION_REQUIRED` (`MICROPHONE`). The web then
  shows the Microphone permission card.
- **No silent listening, no hidden app.** There is no mode without the prompt or the notification, and no capture
  while the app icon is hidden. Requests for one are out of scope.

## Flow

| Step | What happens |
|---|---|
| Start | The web sends `mic.start {deviceId}`. The controller checks the owner, applies the limit (6 per phone per minute), writes an audit line, creates a session id and forwards it with ICE servers |
| Phone accepts the request | It answers `{}`, then asks the owner: directly while the app is in the foreground, otherwise through a "Tap to share your microphone" notification |
| Consent | The owner accepts (`AWAITING_CONSENT` → `STARTING`) or declines (`ENDED DENIED`) |
| Connect | One `mic.signal OFFER` (audio only) from the phone, one `ANSWER` from the browser. No data channel |
| Live | `mic.state LIVE`. The web plays the stream, shows a level meter, kbps and direct/relay |
| Clip | `mic.capture {durationMs}`; the phone records, uploads to `PUT /api/device-api/mic/{mediaId}`, then answers; the browser loads `GET /api/devices/{id}/mic/media/{mediaId}` |
| Stop | Stop on the web, Stop in the phone's notification (`ENDED STOPPED_ON_PHONE`), closing the tab, or the phone going offline |

Wire details: [ws-v1.md](../protocol/ws-v1.md#phase-6-additions-still-v1-all-additive).

## Clips

- **Format:** AAC-LC, mono, 64 kbit/s, in an MPEG-4 container (`.m4a`, `audio/mp4`). 1 s to 5 min, one at a time
  per session, at most 20 per phone per minute.
- **One microphone user:** the phone encodes clips from the same samples it streams, so live audio keeps playing
  while a clip records and nothing else opens the microphone.
- **The controller checks each upload:** the MPEG-4 `ftyp` box (never the phone's claim), the size (20 MiB), and the
  length from the `mvhd` box (at most 5 min plus 1 s).
- **Storage:** clips live in `<storage.dir>/mic` and an in-memory index, never in the database: kept for 30 minutes,
  at most 20 per phone, deleted when the phone is unpaired and when the controller restarts. Save the ones you want
  with Download.
- **The browser's copy** comes with `nosniff` and `Cache-Control: private`, and only for the user's own phones.

## Limits

- One microphone session per phone; a new start replaces the old one.
- **The microphone has one user at a time.** A microphone session can't start while a camera or screen session
  with audio runs (`CONFLICT`). A camera or screen session started while the microphone is shared runs without
  audio.
- The platform's echo cancellation and noise suppression stay on when the phone offers them, so steady background
  noise may be reduced.
- No continuous recording, no recording on the controller, no voice detection, no scheduled capture.

## Android

| Permission / component | Kind | Why |
|---|---|---|
| `RECORD_AUDIO` | Runtime | The microphone. Requested from the Microphone card on the phone, never from the web |
| `FOREGROUND_SERVICE_MICROPHONE`, `MicCaptureService` with `foregroundServiceType="microphone"` | Normal permission + FGS type | Android 14+ requires a typed foreground service for microphone use. It starts after consent, from the consent screen in the foreground |
| `POST_NOTIFICATIONS` (from Phase 1) | Runtime | The consent notification (`mic_requests`) and the ongoing "Microphone shared with …" notification (`mic_capture`) |

**Play policy:** remote listening needs a prominent disclosure and the microphone FGS declaration in the Play
Console. dcontrol is sideloaded, and this page is that disclosure.
