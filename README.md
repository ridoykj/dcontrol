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

| Phase | Features | Plan |
|---|---|---|
| 1 | Accounts with email verification and password reset, JWT + rotating refresh tokens, QR pairing, persistent device connection, live status, ping | [phase-1.md](controller/docs/plans/phase-1.md) |
| 2 | File manager and transfers, notification mirroring, clipboard sync, device info | [phase-2.md](controller/docs/plans/phase-2.md) |
| 3 | Contacts, call log and placing calls, SMS | [phase-3.md](controller/docs/plans/phase-3.md) |
| 4 | Screen mirroring over WebRTC, remote control, find my phone | [phase-4.md](controller/docs/plans/phase-4.md) |
| 5 | Remote camera: live view, photos, clips, front/back, consent on the phone | [phase-5.md](controller/docs/plans/phase-5.md) |
| 6 | Remote microphone: live listening and audio clips, consent on the phone | [phase-6.md](controller/docs/plans/phase-6.md) |

Feature docs are in [controller/docs/features/](controller/docs/features/).

## Quick start

Prerequisites: JDK 25, Docker, and Android Studio with SDK 37 (for the client).

1. Start the controller (it starts PostgreSQL, Mailpit and coturn through Docker Compose):

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

[controller/ai-context/](controller/ai-context/README.md) is the knowledge base for AI agents and people:
hand-written topics (workflow, feature map, architecture, Android, security, database) and files generated from
source on every build. [AGENTS.md](AGENTS.md) (also read through `CLAUDE.md`) is the entry point for coding agents.
