# Notification mirroring

Notifications posted on the phone show up live in the web UI. From there they can be dismissed, and
messaging notifications can be answered with an inline reply. **Nothing is stored:** the controller
validates each event and forwards it to the owner's open browser tabs, and never writes notification
content to the database or the logs.

## Flow

| Step | Message |
|---|---|
| Web opens the Notifications tab | `notifications.list {offset}` → `{access, items, nextOffset}` (all pages are loaded) |
| Phone posts or updates a notification | `notification.posted Notification` → web `notification.posted {deviceId, notification}` |
| Notification goes away on the phone | `notification.removed {key}` → web `notification.removed {deviceId, key}` |
| Web dismisses | `notification.dismiss {key}` → phone `cancelNotification(key)` |
| Web replies | `notification.reply {key, text}` → the phone fills the reply action's `RemoteInput` and sends its `PendingIntent` |

- The phone coalesces updates of the same notification (at most one event per key every 250 ms).
- Text is truncated on the phone (title 200, text 2000 characters) and checked again on the controller.
- The `deviceId` the browser sees is always the authenticated sender's; a value inside the phone's payload
  is ignored.

**App icons** are binary, so they don't travel in the events:

- A notification carries `iconHash`, the SHA-256 of a PNG.
- The phone checks `HEAD /api/device-api/app-icons/{hash}` and uploads with `PUT` if the controller lacks it.
- The web loads `GET /api/devices/{id}/app-icons/{hash}`, cached as immutable.
- Icons must be PNG, at most 32 KiB, and match their hash. They live in `<storage.dir>/icons/<deviceId>/`.

**Desktop notifications** are an opt-in switch on the tab. The browser's permission is requested when the
user turns it on. They are shown only while the tab is in the background, and ongoing notifications are
skipped. The preference is kept in `localStorage`.

## Android permissions

| Permission / mechanism | How it is granted | Notes |
|---|---|---|
| `NotificationListenerService` declared with `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"` | The user enables **Notification access** in Settings; the card on the status screen opens `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` (API 30+) or the list (older) | Bound by the system; no foreground service needed |

- On Android 13+, a sideloaded APK may show the Notification access toggle greyed out. Open **App info → ⋮ →
  Allow restricted settings** first.
- Android 15+ hides the content of sensitive notifications (one-time codes) from third-party listeners. They
  arrive redacted.
- Replies use the posting app's own `PendingIntent`, so no extra permission is needed.
- App label and icon come from `Notification.EXTRA_BUILDER_APPLICATION_INFO`, so the app needs no
  `QUERY_ALL_PACKAGES`.
- The app lists apps whose notifications it has seen. Each has a switch to stop mirroring it, stored on the
  phone.
