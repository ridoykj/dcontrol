# Remote control

During a screen session (see [screen-mirroring.md](screen-mirroring.md)) the browser can control the phone:
- tap, long press, swipe and drag with the mouse;
- scroll with the wheel;
- the phone's system buttons;
- typing into the focused text field.

Input travels on the WebRTC data channel `control`, end to end between the browser tab that started the session
and the phone. It never passes through the controller.

## What the browser sends

| Input | Message | On the phone |
|---|---|---|
| Mouse down / move / up | `down` / `move` / `up` with x, y as 0..1 fractions of the frame; moves are throttled to 60 Hz | `dispatchGesture`: the whole stroke is sent as one gesture when the pointer is released, so a drag shows on the phone only when it ends |
| Wheel | `wheel` with a summed `dy`, at most every 100 ms | A swipe |
| Right click, Esc | `key back` | `performGlobalAction(BACK)` |
| Toolbar | `key` back, home, recents, notifications, power, lock | Global actions (lock needs Android 9+) |
| Typing, paste | `text` (≤ 256 chars per message), `edit backspace`, `edit enter` | `ACTION_SET_TEXT` on the focused field; Enter is `ACTION_IME_ENTER` (Android 11+) |

- Coordinates follow the video frame, which follows the phone's rotation, so the web needs no rotation math.
- Points on the letterbox bars are ignored; a drag that leaves the picture is clamped to its edge.
- The phone drops invalid messages and ends the session after 20.
- Typing is not real key injection, so games and some custom editors ignore it.

## When control is available

The phone reports `control` in the `screen.start` result and in every `screen.state`, and sends
`{"t":"control","available":…}` on the data channel when the service is turned on or off. While it is off, the
web shows the screen as view-only and disables the toolbar.

## Android

| Component | Kind | Why |
|---|---|---|
| `ControlAccessibilityService` (protected by `BIND_ACCESSIBILITY_SERVICE`) | The user turns it on in Settings › Accessibility | Gestures, global actions, setting text in the focused field. Also used by Find my phone to lock the screen |

- No new foreground-service type: input is applied while the `mediaProjection` service of the session runs.
- ⚠ **Sideloaded APK on Android 13+:** the service can't be turned on until **App info › ⋮ › Allow restricted
  settings**. The Remote control card on the phone explains this.
- ⚠ **Google Play:** using the Accessibility API for something other than accessibility requires the
  Accessibility declaration and a prominent disclosure, and approval is up to Google.
- The service never clicks the MediaProjection consent dialog. It is used only to open the consent screen from
  the background (Android's exemption for bound accessibility services).
