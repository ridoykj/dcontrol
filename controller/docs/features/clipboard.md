# Clipboard

Plain-text clipboard in both directions, up to 16,000 characters. Nothing is stored. The web keeps the
received texts for the open tab only.

| Direction | How |
|---|---|
| Web → phone | The Clipboard tab sends `clipboard.set {text}`; the phone calls `ClipboardManager.setPrimaryClip` |
| Phone → web | The user triggers it on the phone; the phone sends `clipboard.changed {text}` and the controller forwards `{deviceId, text, receivedAt}` |

## Why phone → web needs a tap

Since Android 10, only the foreground app or the default keyboard may read the clipboard. A background
service can't watch it. The phone sends its clipboard when the user asks, from any of these:

- the **Send clipboard** button in the app;
- the **Send clipboard** Quick Settings tile (`TileService`);
- the **Send clipboard** action on the connection notification.

The tile and the notification action open a translucent `ClipboardSendActivity`. It reads the clip once its
window has focus, which the system requires, sends it, and closes. Android 12+ shows a "pasted from" toast.

Writing the clipboard is allowed in the background. Android 13+ shows its own copy confirmation when the
web sets the clip.

## Android permissions

| Permission / mechanism | Kind | Notes |
|---|---|---|
| none for reading or writing the clipboard | — | Reads only happen from a visible activity, as described above |
| `TileService` declared with `android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"` | Held by the system | The user adds the tile. On API 34+ it opens the activity with `startActivityAndCollapse(PendingIntent)` |
