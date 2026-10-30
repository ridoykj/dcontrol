# SMS

The Messages tab reads the phone's SMS threads live and sends SMS through the phone's SIM. **Nothing is stored
on the controller**, and message text is never logged.

## Flow

| Step | Message |
|---|---|
| Open the tab | `sms.threads {cursor}` → threads, newest first; participants named from the phone's contacts when CONTACTS is granted |
| Open a thread | `sms.messages {threadId, cursor}` → messages, newest first; "Load older messages" pages back |
| Send | `sms.send {to, text, simId, clientRef}` → `{parts}` once queued, then `sms.status {clientRef, SENT \| DELIVERED \| FAILED}` events |
| New SMS arrives | `sms.received {threadId, message}` |
| Anything else changes | `sms.changed` → the web refetches |

- **Live updates without `RECEIVE_SMS`.** The phone watches the SMS provider with a `ContentObserver` (debounced to
  1 s) while it is connected, and sends `sms.received` for new inbox rows. An incoming SMS shows up once the
  default SMS app has stored it, usually within a second. `RECEIVE_SMS` is deliberately not requested: it is
  the permission Google's sideloading protections block outright.
- **Sending.** The web clears the composer and shows the message as a bubble that moves from "Sending to phone"
  to "Sending", "Sent" and "Delivered" (or "Not sent" with the reason), matched by `clientRef`. The phone uses
  `SmsManager.sendMultipartTextMessage`; Android writes the sent message to the provider itself, so it then
  appears in the phone's Messages app and in the thread.
- **Part counter.** The composer shows how many parts the carrier will bill: 160 / 153 characters per part in
  GSM-7, 70 / 67 once any character outside GSM-7 (e.g. emoji, Polish letters) is used. Messages are capped at
  1,600 characters and 20 parts; the phone rejects longer messages with `PAYLOAD_TOO_LARGE`.
- **Recipients.** Sending takes one `DialNumber` (digits, optional `+`, separators; no `*` or `#`). Replying is
  offered in single-number threads; group threads and alphanumeric senders (`VM-BANK`) are read-only.
- **Limits.** At most 10 `sms.send` per phone per minute (`dcontrol.sms.send-per-minute`); more answer
  `RATE_LIMITED` without closing the connection. Each send writes an audit line (type, device, user; never the
  number or text).
- **MMS** messages appear as a placeholder (`mmsSummary`: "MMS" or "MMS · <subject>"); their parts and
  content aren't read and MMS can't be sent.
- **Not possible without being the default SMS app:** marking as read, deleting messages, sending MMS. dcontrol
  doesn't ask for the SMS role.

## Android permissions

| Permission | Kind | Why |
|---|---|---|
| `READ_SMS` | Runtime ("SMS" group) | Threads, messages, the `ContentObserver` |
| `SEND_SMS` | Runtime ("SMS" group) | Sending; carrier charges apply |
| `READ_CONTACTS` | Runtime, optional | Names instead of numbers in threads |
| `READ_PHONE_STATE` | Runtime, optional | SIM picker |

- Missing SMS permissions answer `PERMISSION_REQUIRED` with `details.permission = SMS`; the web points to the
  **Messages** card on the phone.
- ⚠ **Android 15+ restricted settings.** For an APK installed outside an app store, Android blocks the SMS
  permission request until the user opens **App info → ⋮ → Allow restricted settings**. The card on the phone
  detects this and explains it.
- ⚠ **Google Play:** SMS permissions are restricted. A Play build needs the Permissions Declaration Form;
  "cross-device synchronization or transfer of SMS or calls" is a listed permitted use, but approval is up to
  Google.
- Sent/delivered reports come back to a receiver that isn't exported. No foreground-service type is added:
  everything runs in the existing `connectedDevice` `ConnectionService`.
