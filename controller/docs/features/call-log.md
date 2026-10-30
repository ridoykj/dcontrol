# Call log and calls

The Calls tab shows the phone's call log live, can delete entries, and can make the phone place a call.
**Nothing is stored on the controller.**

## Flow

| Step | Message |
|---|---|
| Open the tab, pick a filter | `calls.list {type, cursor}` → `{items, nextCursor}`, newest first. Filters: incoming, outgoing, missed, voicemail, rejected, blocked, answered elsewhere |
| Delete selected entries | `calls.delete {ids ≤ 100}` → `{deleted}`, after a confirmation |
| Call back | `call.place {number, simId}` → `{}` once the phone handed the call to Telecom |
| SIM choice | `telephony.sims {}` → active SIMs and the default SIMs (the picker appears with two or more) |
| Call log changed on the phone | `calls.changed` → the web refetches |

- Withheld numbers arrive as `number: null` with `presentation` `RESTRICTED`, `UNKNOWN` or `PAYPHONE` and can't
  be called back.
- **Placing a call** asks for confirmation ("Your phone will dial …"). The call happens on the phone; no audio
  goes through the browser. The phone uses `TelecomManager.placeCall`, which works from the background service
  because Telecom shows the in-call screen itself.
- **No MMI/USSD.** `call.place` and `sms.send` take a `DialNumber`: digits, an optional leading `+`, and the
  separators space, `(`, `)`, `-`, `.`. `*` and `#` are rejected by the web, the controller and the phone, so a
  browser session can never run codes like `*#06#` or set up call forwarding (`**21*…#`).
- **Limits.** At most 3 `call.place` per phone per minute (`dcontrol.calls.place-per-minute`); more answer
  `RATE_LIMITED`. Every placed call and every delete writes an audit line (type, device, user; never the number).

## Android permissions

| Permission | Kind | Why |
|---|---|---|
| `READ_CALL_LOG` | Runtime ("Call logs" group) | List the call log |
| `WRITE_CALL_LOG` | Runtime (same group) | Delete entries |
| `CALL_PHONE` | Runtime ("Phone" group) | Place calls |
| `READ_PHONE_STATE` | Runtime ("Phone" group), optional | List SIMs for the picker; without it the phone's default SIM is used |

- Missing permissions answer `PERMISSION_REQUIRED` with `details.permission` `CALL_LOG` or `PHONE`; the web
  points to the **Calls** card on the phone.
- ⚠ **Google Play:** Call Log permissions are restricted. A Play build needs the Permissions Declaration Form;
  "cross-device synchronization or transfer of SMS or calls" is a listed permitted use, but approval is up to
  Google. Sideloaded builds are unaffected.
- The phone declares `android.hardware.telephony` as not required. Without telephony, `call.place` answers
  `UNSUPPORTED` and `telephony.sims` reports `hasTelephony: false`.
- No foreground-service type is added.
