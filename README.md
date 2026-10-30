# dcontrol

Manage your Android phone from a web browser, AirDroid-style.

| App | What | Docs |
|---|---|---|
| [`controller/`](controller/) | Spring Boot 4.1 + React web app: REST API, WebSocket relay, SPA | [controller/README.md](controller/README.md) |
| [`client/`](client/) | Android app (Kotlin, Compose) that keeps an outbound connection and runs commands | [client/README.md](client/README.md) |

The phone opens an outbound WebSocket to the controller, so it works behind NAT. The browser talks to
the controller, and the controller relays commands to the phone using a versioned JSON protocol:
[controller/docs/protocol/ws-v1.md](controller/docs/protocol/ws-v1.md).

## Status

**Phase 1 (foundation):**

- Accounts with email verification and password reset.
- JWT access tokens and rotating refresh tokens.
- QR pairing.
- Persistent device connection with heartbeats and backoff.
- Live online/offline and battery status, plus a ping command.

See [controller/docs/plans/phase-1.md](controller/docs/plans/phase-1.md).

Phases 2–4 are not started yet. Planned:

- File manager.
- Notification mirroring.
- Clipboard sync.
- Device dashboard.
- Contacts, call log and SMS.
- Screen mirroring and remote control.
- Find my phone.

## Quick start

Prerequisites: JDK 25, Docker, and Android Studio with SDK 37 (for the client).

1. Start the controller (it starts PostgreSQL and Mailpit through Docker Compose):

   ```bash
   cd controller && ./mvnw spring-boot:run
   ```

2. Open http://localhost:8080, register, and click the link in Mailpit (http://localhost:8025).
3. Build and run the app on an emulator:

   ```bash
   cd client && ./gradlew installDebug
   ```

4. In the web UI choose **Pair a device**. On an emulator, open the QR payload with `adb` (see
   [client/README.md](client/README.md)). On a real phone, scan the code; first set
   `DCONTROL_PUBLIC_BASE_URL` to your PC's LAN address.

## Knowledge base

`controller/ai-context/topics/` holds the hand-written architecture, database, security and Android notes.
`controller/ai-context/generated/` is regenerated from source on every build.
