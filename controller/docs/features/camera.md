# Camera

The Camera tab opens the phone's front or back camera in the browser: a live viewfinder, full-resolution photos,
and short clips with microphone audio. The phone owner has to accept every session on the phone. The live view
goes peer to peer over WebRTC, or through coturn, like [screen mirroring](screen-mirroring.md). Photos and clips are
uploaded to the controller, which keeps them for a short time.

## Consent and visibility

The camera, unlike MediaProjection, has no system dialog for each use. dcontrol's own prompt is therefore the only
thing between a remote viewfinder and covert surveillance, and the feature refuses to work without it:

- **Every session needs the owner's yes on the phone:** "Share your camera with <viewer>?". It is never accepted
  automatically. Without an answer within 60 s, the controller ends the session (`TIMEOUT`).
- **While it runs**, the phone keeps a foreground service of type `camera` (plus `microphone` when audio is on)
  with an ongoing "Camera shared with <viewer>" notification and a **Stop** action. Android's camera and microphone
  indicators stay on the whole time.
- **The session ends** when the owner taps Stop, the browser tab stops or closes, or the phone goes offline.
- **Without the `CAMERA` permission** the phone shows nothing and answers `PERMISSION_REQUIRED`. The web then shows
  the Camera permission card.
- **No silent capture, no hidden app.** There is no mode without the prompt or the notification, and no capture
  while the app icon is hidden. Requests for one are out of scope.

## Flow

| Step | What happens |
|---|---|
| Start | The web sends `camera.start {facing, audio}`. The controller checks the owner, applies the limit (6 per phone per minute), writes an audit line, creates a session id and forwards it with ICE servers |
| Phone accepts the request | It answers `{audio}`, then asks the owner: directly while the app is in the foreground, otherwise through a "Tap to share your camera" notification |
| Consent | The owner accepts (`camera.state AWAITING_CONSENT` → `STARTING`) or declines (`ENDED DENIED`) |
| Connect | One `camera.signal OFFER` from the phone, one `ANSWER` from the browser (non-trickle). There is no data channel |
| Live | `camera.state LIVE` with the camera in use and whether clips are available. The web shows fps, bitrate and direct/relay |
| Switch | `camera.switch {facing}`; the live view continues on the same track |
| Photo / clip | `camera.capture {kind, durationMs}`; the phone uploads to `PUT /api/device-api/camera/{mediaId}`, then answers; the browser loads `GET /api/devices/{id}/camera/media/{mediaId}` |
| Stop | Stop on the web, Stop in the phone's notification (`ENDED STOPPED_ON_PHONE`), closing the tab, or the phone going offline |

Wire details: [ws-v1.md](../protocol/ws-v1.md#phase-5-additions-still-v1-all-additive).

## Captures

- **Photos** are full-resolution JPEGs. **Clips** are MP4 (H.264/AAC), 1–60 s, with audio when the session has
  audio. One capture runs at a time per session, at most 20 per phone per minute.
- **The controller checks each upload:**
  - the type from the magic bytes (JPEG `FF D8 FF`, MP4 `ftyp`), never from the phone's claim;
  - the size (photo 20 MiB, clip 200 MiB);
  - a clip's length from its MP4 `mvhd` box (at most 60 s plus 1 s).
- **Storage:** captures live in `<storage.dir>/camera` and an in-memory index, never in the database:
  - kept for 30 minutes, at most 20 per phone (the oldest go first);
  - deleted when the phone is unpaired;
  - a controller restart deletes them all.

  Save the ones you want with Download.
- **The browser's copy** comes with `nosniff` and `Cache-Control: private`, and only for the user's own phones.

## Limits

- One camera session per phone; a new start replaces the old one. It is independent of a screen session.
- Some phones can't run preview, photo and video capture at the same time. Those sessions report `video: false`
  and offer photos only.
- **Clip audio follows the phone's camcorder profile.** CameraX takes the audio codec from it, and 1.6 has no
  public way to choose. HD profiles on phones record AAC, which browsers play. The Android emulator's profiles
  record AMR-NB: its clips have a sound track, but browsers play them silent.
- No continuous recording, no recording on the controller, no motion detection, no scheduled capture.

## Android

| Permission / component | Kind | Why |
|---|---|---|
| `CAMERA` | Runtime | The camera itself. Requested from the Camera card on the phone, never from the web |
| `RECORD_AUDIO` (from Phase 4) | Runtime | Microphone audio for the live view and clips. Without it the session runs without audio |
| `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE`, `CameraCaptureService` with `foregroundServiceType="camera\|microphone"` | Normal permissions + FGS types | Android 14+ requires a typed foreground service for camera and microphone use. It starts after consent, from the consent screen in the foreground |
| `POST_NOTIFICATIONS` (from Phase 1) | Runtime | The consent notification (`camera_requests`) and the ongoing "Camera shared with …" notification (`camera_capture`) |

**Play policy:** a remote camera needs a prominent disclosure and the camera/microphone FGS declarations in the Play
Console. dcontrol is sideloaded, and this page is that disclosure.
