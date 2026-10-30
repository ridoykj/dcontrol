# Find my phone

The Find tab rings, locates and locks a phone.

## Ring

`find.ring {action: START | STOP}`:
- It plays the default alarm sound at full alarm volume, even in silent mode, until it's stopped from the web, from
  the phone's notification, or after 5 minutes. The previous volume is restored afterwards.
- Limit: `dcontrol.find.ring-per-minute` (10 per phone). Every ring is audited.

## Locate

`POST /api/devices/{id}/location/refresh` sends `find.locate` to the phone.
- The phone tries a fresh fix for 10 s with `LocationManagerCompat.getCurrentLocation` (no Google Play services),
  then falls back to the last known fix and marks it `stale`.
- The controller **stores the result as the device's last known location** (`T_DEVICE_LOCATION`, one row per
  device), so the web can show where the phone was last seen even while it's offline.
- **The web shows:**
  - the coordinates, accuracy, age and provider;
  - links to OpenStreetMap and Google Maps.

  There is no embedded map, so no tile requests leave the browser until a link is clicked.
- **Forget location** (`DELETE /api/devices/{id}/location`) physically deletes the row. Unpairing the phone does
  too. This is a documented exception to the logical-delete rule: a hidden copy of a location the user deleted would
  be misleading.

## Lock with a message

`find.lock {message 1–200, contact?}`:
- Locks the screen with the Accessibility global action and posts a lock-screen notification with the message and
  contact number.
- The phone's own PIN, pattern or biometrics unlock it; dcontrol does not change any credential.
- Limit: `dcontrol.find.lock-per-minute` (5 per phone). Every lock is audited.

## Android

| Feature | Permission | Kind | Notes |
|---|---|---|---|
| Ring | none | — | `USAGE_ALARM` playback from the existing `connectedDevice` service |
| Locate | `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, then `ACCESS_BACKGROUND_LOCATION` | Runtime, two steps (Android 11+: choose "Allow all the time" in Settings) | Locating happens while the app is in the background inside the `connectedDevice` service, which has no `location` type. Without it: `PERMISSION_REQUIRED` `LOCATION`; with location turned off: `UNSUPPORTED` |
| Lock | `ControlAccessibilityService` | Turned on in Settings › Accessibility | Without it: `PERMISSION_REQUIRED` `ACCESSIBILITY` |
| Lock message | `POST_NOTIFICATIONS` (from Phase 1) | Runtime | Public notification visible on the lock screen |

- No device admin and no new foreground-service type.
- ⚠ **Google Play:** background location needs the location permissions declaration, and Accessibility needs its
  own declaration (see [remote-control.md](remote-control.md)). Sideloaded builds are unaffected apart from
  Android 13+ restricted settings for Accessibility.
