# dcontrol controller

Spring Boot 4.1 app (Java 25) that serves the REST API, the WebSocket relay between browsers and phones, and
the React SPA. The Maven build also installs Node, builds and tests the frontend, and refreshes
`ai-context/generated/`.

## Requirements

- JDK 25 (`JAVA_HOME`)
- Docker (Docker Desktop on Windows/macOS). Dev uses it via `compose.yaml` (PostgreSQL 18, Mailpit, coturn), and
  the tests use it via Testcontainers.
- Node is **not** required globally: Maven downloads Node 26 into `controller/node/`.

## Run locally

```bash
./mvnw spring-boot:run
```

The `dev` profile (active by default) starts `compose.yaml` automatically and uses local defaults:

| URL | What |
|---|---|
| http://localhost:8080 | App (built SPA + API) |
| http://localhost:8025 | Mailpit: verification and reset emails |
| localhost:5432 | PostgreSQL (`dcontrol` / `dcontrol`) |
| localhost:3478 (UDP/TCP), 40000–40040/udp | coturn: STUN/TURN for screen sessions |

For frontend work with hot reload, run the backend as above, then start Vite and open http://localhost:5173.
Vite proxies `/api` and `/ws` to :8080.

```bash
cd src/main/frontend && ../../../node/npm run dev
```

On Windows, use `..\..\..\node\npm.cmd run dev`.

### Pairing a phone in development

The QR code carries `dcontrol.public-base-url`, which has to be reachable **from the phone**:

| Phone | Setting |
|---|---|
| Android emulator | default `http://10.0.2.2:8080` |
| Real phone on Wi-Fi | `DCONTROL_PUBLIC_BASE_URL=http://<your LAN IP>:8080` (allow port 8080 in your firewall) |

For screen sessions with a real phone, also put `TURN_EXTERNAL_IP=<your LAN IP>` in `controller/.env` (read by
`docker compose`) and allow UDP 3478 and 40000–40040 in your firewall.

## Build and test

```bash
./mvnw verify
```

That runs npm ci, the ai-context sync, the Vite build, Vitest, the backend unit tests, and the Testcontainers
integration tests (including `SchemaConventionIntegrationTest`). Useful variants:

```bash
./mvnw verify -Dskip.npm
```

```bash
./mvnw test -Dtest=SchemaConventionIntegrationTest -Dskip.npm
```

## Configuration

| Env var | Purpose | Dev default |
|---|---|---|
| `DCONTROL_JWT_SECRET` | HS256 signing key, ≥ 32 bytes | local value in `application-dev.yaml` |
| `DCONTROL_PUBLIC_BASE_URL` | URL phones use; goes into the QR code | `http://10.0.2.2:8080` |
| `DCONTROL_WEB_BASE_URL` | Base of links in emails | `http://localhost:5173` |
| `DCONTROL_MAIL_HOST`, `DCONTROL_MAIL_PORT`, `DCONTROL_MAIL_USERNAME`, `DCONTROL_MAIL_PASSWORD`, `DCONTROL_MAIL_FROM` | SMTP | Mailpit on `localhost:1025` |
| `DCONTROL_TURN_SECRET` | coturn `static-auth-secret`; signs TURN credentials. Required when an ICE server has `turn: true` | `dev-turn-secret` (same in `compose.yaml`) |
| `DCONTROL_STORAGE_DIR` | Transfer spool and app-icon cache (local disk, single instance) | `${java.io.tmpdir}/dcontrol` |
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Database outside dev | from compose |
| `SPRING_PROFILES_ACTIVE` | Set to something other than `dev` in production (disables compose, secure cookies) | `dev` |

Other limits live in `application.yaml` and can be overridden like any Spring property:

| Property | Purpose | Default |
|---|---|---|
| `dcontrol.sms.send-per-minute` | SMS a browser may send through one phone per minute | `10` |
| `dcontrol.calls.place-per-minute` | Calls a browser may start on one phone per minute | `3` |
| `dcontrol.blobs.max-size`, `dcontrol.blobs.timeout` | In-memory uploads phones answer with (contact photos) | `256KB`, `20s` |
| `dcontrol.webrtc.ice-servers` | STUN/TURN URLs (`{host}` = host each socket connected to); `turn: true` adds credentials | STUN + TURN on `{host}:3478` |
| `dcontrol.webrtc.credential-ttl` | TURN credential lifetime; also the longest possible screen session | `12h` |
| `dcontrol.webrtc.consent-timeout` | Time to tap Start on the phone | `60s` |
| `dcontrol.webrtc.start-per-minute` | Screen sessions per phone per minute | `6` |
| `dcontrol.find.ring-per-minute`, `dcontrol.find.lock-per-minute` | Find my phone limits per phone | `10`, `5` |
| `dcontrol.camera.consent-timeout` | Time for the phone owner to accept a camera session | `60s` |
| `dcontrol.camera.start-per-minute`, `dcontrol.camera.capture-per-minute` | Camera sessions and captures per phone per minute | `6`, `20` |
| `dcontrol.camera.capture-timeout` | Longest a capture may take, clip plus upload; must exceed `max-video-duration` | `2m` |
| `dcontrol.camera.max-photo-size`, `dcontrol.camera.max-video-size` | Largest photo and clip a phone may upload | `20MB`, `200MB` |
| `dcontrol.camera.max-video-duration` | Longest clip | `60s` |
| `dcontrol.camera.media-retention`, `dcontrol.camera.max-retained` | How long captures stay in `<storage.dir>/camera`, and how many per phone | `30m`, `20` |
| `dcontrol.mic.consent-timeout` | Time for the phone owner to accept a microphone session | `60s` |
| `dcontrol.mic.start-per-minute`, `dcontrol.mic.capture-per-minute` | Microphone sessions and clips per phone per minute | `6`, `20` |
| `dcontrol.mic.capture-timeout` | Longest a clip may take, recording plus upload; must exceed `max-audio-duration` | `6m` |
| `dcontrol.mic.max-audio-size`, `dcontrol.mic.max-audio-duration` | Largest and longest audio clip a phone may upload | `20MB`, `5m` |
| `dcontrol.mic.media-retention`, `dcontrol.mic.max-retained` | How long clips stay in `<storage.dir>/mic`, and how many per phone | `30m`, `20` |

## Layout

```
compose.yaml            PostgreSQL 18, Mailpit and coturn for dev
ai-context/topics/      hand-written knowledge base (architecture, DB conventions, security, Android)
ai-context/generated/   rebuilt by sync.mjs during the build; do not edit
docs/features/          one doc per feature
docs/protocol/ws-v1.md  WebSocket protocol contract
docs/plans/             phase plans and decisions
src/main/java/com/dcontrol/controller/{common,auth,pairing,device,spa}
src/main/resources/db/migration   Flyway (run SchemaConventionIntegrationTest after changes)
src/main/frontend/      React + TanStack Router + Tailwind + React Aria
```

Do not edit `src/main/resources/static/` (build output), `src/main/frontend/src/routeTree.gen.ts`
(generated), or `src/main/frontend/src/shared/components/ui/shadcn/` (regenerate it with the shadcn CLI
from `src/main/frontend`):

```bash
../../../node/npx shadcn add https://react-aria.adobe.com/registry/tailwind-<component>.json
```
