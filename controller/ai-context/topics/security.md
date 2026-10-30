# Security model

## Principals

| Principal | Credential | Where checked |
|---|---|---|
| User (browser) | HS256 access JWT (`typ=access`, `aid`), 15 min | Spring resource server on `/api/**` |
| User session | Rotating refresh token in an HttpOnly SameSite=Strict cookie | `AuthService.refresh` |
| Browser socket | One-time 30 s ticket | `WebHandshakeInterceptor` |
| Device | `Authorization: Device <id>.<secret>`; SHA-256 of the 256-bit secret in `T_DEVICE` | `DeviceHandshakeInterceptor` → `DeviceAuthService` |
| Pairing phone | Pairing token (3 min, single use) | `PairingService.claimable` |

`BearerTokenResolver` ignores the `Authorization` header on `/api/auth/**`, `/api/device-api/**` and
`/ws/**`. A stale bearer token therefore can't break login or refresh, and the `Device` scheme never reaches
the JWT decoder.

## Rules

- **Tenancy.** Every business table has `id_account_key`. `@TenantId` filters every JPQL query and stamps
  inserts. Only the SYSTEM actor (root) crosses accounts, and only in explicit `actorTx` blocks for lookups
  that happen before we know the account: login, refresh, pairing token, device auth, jobs. A device or
  pairing that belongs to another account returns 404 / `NOT_FOUND`, the same as one that doesn't exist.
- **Untrusted phones.** All device input is validated (`MessageCodec` + Bean Validation), size and rate
  limited, and can only complete its own pending commands. Relayed results are validated before they reach
  the browser, and only an allow-list of error codes passes through. Event payloads never choose the device:
  the controller stamps the authenticated device id. Phone HTTP calls (`/api/device-api/transfers`,
  `/app-icons`) authenticate with the device secret and only reach that device's transfers and icons.
- **Phone-supplied content.** File downloads are always `application/octet-stream` + `Content-Disposition:
  attachment` + `nosniff` + `no-store`, with sanitized file names. Icons must be PNG (magic bytes), at most
  32 KiB and match their SHA-256. Notification, clipboard, contact, call log and SMS data is relayed, never stored or logged. Contact photos go
  through `PhoneBlobRelay` (memory only, JPEG/PNG magic bytes, ≤ 256 KiB, single use, bound to the device that
  was asked) and reach the browser with `nosniff` and `private, max-age=300`.
- **Actions that cost money or reach other people.** `sms.send` (10/min) and `call.place` (3/min) are limited per
  device and audited (type, device, user; never numbers or text). `DialNumber` only allows digits, a leading `+` and
  separators, so the web can never run MMI/USSD codes (`*#06#`, call forwarding `**21*…#`); the phone checks again.
  `contacts.delete` and `calls.delete` are audited too, because contact deletes propagate to synced accounts.
- **Screen sessions (Phase 4).**
  - A session is bound to the browser socket that started it. Only that socket gets the session's signaling, and
    only it may answer or stop it.
  - Media and input never pass through the controller.
  - `screen.start` is limited (6/min per phone) and audited.
  - Android requires someone to tap Start on the phone for every session. dcontrol never auto-clicks the consent
    dialog with its Accessibility service.
  - SDP and ICE credentials are never logged.
- **Camera sessions (Phase 5).**
  - The camera has no mandatory system consent dialog the way MediaProjection does, so the app's own prompt is the
    only gate. The phone owner accepts every session on the phone, and it is never automatic. The phone shows an
    ongoing notification with Stop for the whole session, and the FGS type `camera` (plus `microphone` with audio)
    keeps the system privacy indicators on. Without the `CAMERA` grant the phone answers `PERMISSION_REQUIRED` and
    shows nothing. There is no hidden mode.
  - Sessions are bound to one browser socket, like screen sessions; capture and switch commands follow the same
    ownership rules.
  - `camera.start` (6/min) and `camera.capture` (20/min) are limited per phone and audited (type, device, user).
    Audit lines never contain media, SDP or ICE credentials.
  - Captures go into `CameraMediaService`:
    - each upload slot is single use and bound to the phone that was asked;
    - the type comes from the magic bytes (JPEG `FF D8 FF`, MP4 `ftyp`), and a clip's length from its `mvhd` box;
    - captures are capped in size and length, kept on disk for 30 min (at most 20 per phone), never in the
      database, and deleted when the phone is unpaired;
    - the browser gets them with `nosniff` and `private`, and only for its own phones. The CSP allows `blob:` only
      for `img-src` and `media-src`, so the page can show captures it already fetched.
- **Microphone sessions (Phase 6).**
  - The same consent contract as the camera: the app's own prompt on every session, never automatic; an FGS of
    type `microphone` with an ongoing notification and Stop keeps the mic indicator on. Without `RECORD_AUDIO` the
    phone answers `PERMISSION_REQUIRED` (`MICROPHONE`) and shows nothing. No hidden mode.
  - Sessions are bound to one browser socket; `mic.start` (6/min) and `mic.capture` (20/min) are limited per phone
    and audited without media, SDP or ICE credentials.
  - Clips go through the same `CaptureMediaStore` as camera captures (single-use slot bound to the phone, MP4 `ftyp`
    and `mvhd` length checked, 20 MiB / 5 min caps, 30 min on disk, deleted on unpair), spooled in
    `<storage.dir>/mic`.
  - The phone gives the microphone to one session at a time, so a camera or screen session can't silently share it
    with a microphone session.
- **TURN.**
  - Credentials are short-lived HMACs of `<expiry>:<user id>` (coturn `use-auth-secret`), one set per socket.
  - The `{host}` placeholder only accepts a plain host name or IP literal from the `Host` header. Anything else
    falls back to the host of `public-base-url`.
- **Find my phone.**
  - `find.lock` (5/min) and `find.ring` (10/min) are limited and audited.
  - The lock uses the phone's own screen lock. dcontrol never sets or changes credentials, and has no wipe.
  - The last location fix is the only stored phone data. "Forget location" and unpairing delete it physically.
- **Download tickets.** Native downloads can't send the bearer token, so `/api/transfers/{id}/content`
  takes a single-use, 60 s ticket bound to one transfer. Tokens never go into URLs.
- **Secrets at rest.** Only hashes are stored for refresh tokens, pairing tokens and device secrets.
  Passwords use the `DelegatingPasswordEncoder` (bcrypt).
- **CSRF.** The API is stateless bearer. The cookie endpoints (`refresh`, `logout`) need `X-Requested-With`
  and rely on SameSite=Strict.
- **Enumeration.** Resend-verification and forgot-password always return 202. Login returns the same
  `INVALID_CREDENTIALS` and does a dummy bcrypt check for unknown emails.
- **Headers.** CSP `default-src 'self'` (inline styles allowed for React Aria), `frame-ancestors 'none'`,
  `Referrer-Policy: no-referrer`.
- **Open redirect.** `/login?redirect=` only accepts same-origin paths.
- **Pairing takeover.** The phone shows the controller host and the masked account email before claiming.
  Release builds refuse cleartext controller URLs.

## Production checklist

- Set `DCONTROL_JWT_SECRET` (≥ 32 random bytes), `DCONTROL_PUBLIC_BASE_URL` (https) and `DCONTROL_WEB_BASE_URL`.
- Set `DCONTROL_STORAGE_DIR` to a persistent, private directory (transfer spool and app icons); size it for
  10 GiB of open transfers per active account.
- Run behind TLS. Keep `dcontrol.cookie.secure=true` (the default outside the dev profile).
- If you run behind a reverse proxy, set `server.forward-headers-strategy=framework` so rate limits see client IPs.
- Set `DCONTROL_TURN_SECRET` to a long random value, the same as coturn's `static-auth-secret`, and point
  `dcontrol.webrtc.ice-servers` at your TURN host.
- Run coturn with TLS (`turns:` on 5349) and `denied-peer-ip` for private and loopback ranges, so the relay can't be
  used to reach your internal network.
