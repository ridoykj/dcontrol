# Persistent device connection

The phone keeps an outbound WebSocket to the controller, so it is reachable behind NAT. The controller
tracks presence and battery, pushes status to the browser, and relays commands with request/response
correlation. The wire format is specified in [`../protocol/ws-v1.md`](../protocol/ws-v1.md).

## Channels

| Path | Client | Authentication |
|---|---|---|
| `/ws/device` | Android `ConnectionService` (OkHttp) | `Authorization: Device <deviceId>.<secret>`; 401 at handshake otherwise |
| `/ws/web?ticket=` | Browser | One-time ticket from `POST /api/ws/tickets` (30 s); browsers can't set headers on a WebSocket handshake |

The browser endpoint only accepts its own origin plus `dcontrol.ws.allowed-origins` (dev: the Vite server).

## Lifecycle

1. The phone connects and sends `device.hello` (app/OS facts + battery). Without it, the socket is closed
   with `4400` after 10 s.
2. The controller answers `hello.ack {serverTime, heartbeatIntervalSec: 30, deviceName}`, registers the
   connection, writes `T_DEVICE_STATUS`, updates changed device facts in `T_DEVICE`, and pushes
   `device.status` to the account's browsers.
3. The phone sends `device.heartbeat {battery}` every 30 s. OkHttp also pings every 20 s to keep NAT
   mappings alive. Battery changes are pushed to browsers immediately. `T_DEVICE_STATUS` is written at most
   every 5 min from heartbeats, to keep write volume low.
4. A socket silent for 75 s is closed. On close, pending commands fail with `DEVICE_DISCONNECTED`, the last
   seen time and battery are persisted, and browsers get `device.status {online: false}`.
5. The phone reconnects with full-jitter exponential backoff, `random(0, min(60 s, 1 s × 2^attempt))`. The
   backoff resets after `hello.ack`, and the phone reconnects at once when the network comes back.

A newer connection for the same device supersedes the older one (`4009`). Unpairing from the web closes
the socket with `4003`; the phone then wipes its credentials and shows "unpaired".

## Command relay

```
browser --device.ping {deviceId}, requestId=B--> controller
  checks the device belongs to the caller's account (else NOT_FOUND) and is connected (else DEVICE_OFFLINE)
controller --device.ping, requestId=R (new)--> phone
phone --device.ping.result, requestId=R--> controller
controller --device.ping.result {rttMs, deviceTime}, requestId=B--> browser
```

- Pending commands live in `PendingRequestRegistry`, keyed by the relay id. They time out after 15 s
  (`TIMEOUT`).
- A result only completes a command sent to the same device. Unknown ids are dropped.
- Device error codes outside the known set are reported as `INTERNAL`, and device messages are truncated to
  200 characters.

Phase 1 implements `device.ping`. Later commands reuse `DeviceCommandRelay.relay(...)`.

## Validation and limits (phones are untrusted)

- Text frames ≤ 64 KiB (`1009` otherwise). Envelope `v` must be 1, `type` is required, payloads are validated
  with Bean Validation. Every violation is answered with `error {code: VALIDATION}`, and the third one closes
  with `4400`.
- 30 messages / 10 s per device socket and 20 / 10 s per browser socket (`4429`).
- Device sockets write as `Actor.device(account, device)`, so tenancy applies to everything they touch.

## Presence data

| Where | What |
|---|---|
| `PresenceService` (memory) | Online devices, last battery, last frame time |
| `DeviceSessionRegistry` (memory) | deviceId → open socket |
| `T_DEVICE_STATUS` | Last seen, battery level, charging; survives restarts |

All of these are single-node structures. Running several controller instances needs a shared registry
(for example Redis pub/sub) and sticky routing. That is out of scope for Phase 1.

## Android side

- `ConnectionService` is a foreground service of type `connectedDevice` that owns the socket. `BootReceiver`
  starts it after reboot or an app update when the phone is paired.
- `BatteryReader` reads the sticky `ACTION_BATTERY_CHANGED` broadcast, which needs no permission.
- `NetworkMonitor` (a `ConnectivityManager` callback) triggers an immediate reconnect.

### Permissions and foreground-service type

| Permission / setting | Kind | How it is granted |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | normal | At install |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | normal | At install |
| `CHANGE_NETWORK_STATE` | normal | At install. It is one of the prerequisites Android 14+ checks for the `connectedDevice` type. |
| `POST_NOTIFICATIONS` (API 33+) | runtime | Asked during onboarding. If denied, the service still runs, but its notification only appears in the Task Manager. |
| `RECEIVE_BOOT_COMPLETED` | normal | At install. `connectedDevice` isn't among the types Android 15 forbids from `BOOT_COMPLETED`. |
| Battery optimization exemption | user setting | The app opens `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`; the user turns optimization off for dcontrol. Some OEMs kill background services aggressively without this. We deliberately avoid `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which Play restricts. |

A process with a running foreground service keeps network access in Doze, so heartbeats continue
while the screen is off.

> **Play Store flag:** Google Play reviewers may question `connectedDevice` for a "manage the phone from a
> browser" use case. If they do, switch to `specialUse` with a
> `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification and declare it in the Play Console.
