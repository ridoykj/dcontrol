# Screen mirroring

The Screen tab shows the phone's screen live in the browser, optionally with the phone's media audio. Video and
audio go peer to peer over WebRTC, or through the coturn relay when a direct path fails. **The controller only
relays signaling and never sees the media.** Remote input is described in [remote-control.md](remote-control.md).

## Flow

| Step | What happens |
|---|---|
| Start | The web sends `screen.start {quality, audio, control}`. The controller checks the owner, applies the limit (6 per phone per minute), writes an audit line, creates a session id and forwards it with ICE servers |
| Phone accepts | It answers `{audio, control}` (what it can provide), then shows the consent screen: directly while the Accessibility service is on, otherwise as a "Tap to share your screen" notification |
| Consent | Someone taps **Start** on the phone (`screen.state AWAITING_CONSENT` → `STARTING`). Without a tap within 60 s, the controller ends the session with `TIMEOUT` |
| Connect | The phone gathers ICE and sends one `rtc.signal OFFER`; the browser answers with one `rtc.signal ANSWER` (non-trickle) |
| Live | `screen.state LIVE`; the web shows fps, bitrate, and whether the path is direct or relayed |
| Stop | Stop on the web (`screen.stop`), Stop in the phone's notification (`ENDED STOPPED_ON_PHONE`), closing the tab, or the phone going offline |

- Each session belongs to the browser tab that started it; signaling for it never reaches other tabs.
- A new start on the same phone replaces the old session, whose tab sees "started from another browser tab".
- Wire details: [ws-v1.md](../protocol/ws-v1.md#phase-4-additions-still-v1-all-additive).

## Quality

| Preset | Long edge | Frame rate | Max bitrate |
|---|---|---|---|
| Low | 720 px | 15 fps | 1 Mbps |
| Medium (default) | 1280 px | 30 fps | 2.5 Mbps |
| High | 1920 px | 30 fps | 5 Mbps |

## TURN and ICE

- `compose.yaml` runs `coturn/coturn:4.18.0-alpine` on port 3478 (UDP/TCP) with relay ports 49160–49200/udp.
- **Credentials:** the controller hands each socket short-lived TURN credentials in coturn's
  `use-auth-secret` scheme, signed with `dcontrol.webrtc.turn-secret`. They are valid for
  `dcontrol.webrtc.credential-ttl` (12 h); coturn refuses allocation refreshes after that, which caps a session's
  length.
- **`{host}` in the ICE URLs** is replaced with the host each socket connected to, so the browser
  (`localhost`) and the emulator (`10.0.2.2`) reach the same coturn.
- **A real phone on Wi-Fi:** set `TURN_EXTERNAL_IP` to the PC's LAN IP in `controller/.env`.

## Limits

- Android 14+ requires consent for **every** session. Unattended access is not possible, and dcontrol never
  auto-clicks the consent dialog.
- Screens of apps that set `FLAG_SECURE` (banking, DRM video) are black. Android 15+ hides sensitive
  notifications (such as one-time codes) while the screen is shared.
- One viewer per phone. No recording.

## Android

| Permission / component | Kind | Why |
|---|---|---|
| `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `ScreenCaptureService` with `foregroundServiceType="mediaProjection"` | Normal permission + FGS type | Required for MediaProjection on Android 14+. The service starts after consent and before `getMediaProjection()` |
| `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, FGS type `microphone` (only while audio is on) | Runtime + FGS type | Phone audio through `AudioPlaybackCapture`. The mic privacy indicator shows while audio is on. Microphone samples are never sent |
| `POST_NOTIFICATIONS` (from Phase 1) | Runtime | The consent notification (channel `screen_requests`) and the ongoing "Screen shared with …" notification |

- The consent dialog is created with `MediaProjectionConfig.createConfigForDefaultDisplay()` on API 34+, so it
  shares the whole screen.
- Apps that opt out of playback capture are silent; calls are never captured.
