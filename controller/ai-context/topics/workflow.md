# Working on dcontrol

How changes are built, checked and documented. Commands assume the repo root unless they `cd`.

## Build and test gates

A change is done when both gates pass:

| App | Command | Runs |
|---|---|---|
| Controller | `cd controller && ./mvnw verify` | `npm ci`, `npm run ai:sync`, Vite build, Vitest, JUnit, Testcontainers integration tests (needs Docker, ~3 min) |
| Client | `cd client && ./gradlew testDebugUnitTest lintDebug assembleDebug` | JVM + Robolectric unit tests, Android lint, debug APK |

Faster loops:

- Backend only: `./mvnw verify -Dskip.npm` (skips the frontend build and its tests).
- One test class: `./mvnw verify -Dskip.npm -Dtest=CameraSessionIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
  (`*IntegrationTest` classes use `support/IntegrationTest`, which starts the Testcontainers).
- Frontend, from `controller/src/main/frontend`: `npm test` (Vitest), `npm run build` (Vite + `tsc`),
  `npm run typecheck`.
- Client, one module: `./gradlew :feature:camera:testDebugUnitTest`; `:core:websocket:test` needs no Android SDK.

The Maven build installs its own Node into `controller/node/`. If the global `npm` is broken, put that folder first
on `PATH` and call the tools through it.

## Running it

- Controller: `cd controller && ./mvnw spring-boot:run` (dev profile; Docker Compose starts PostgreSQL 18, Mailpit
  on :8025 and coturn on 3478 with relay ports 40000–40040). The SPA is served on :8080; `npm run dev` gives Vite on
  :5173 with a proxy.
- Client: `cd client && ./gradlew installDebug`. Emulators reach the host as `10.0.2.2`; debug builds pair through
  the `dcontrol://pair?...` deep link (`client/README.md`).
- Client Gradle needs a JDK that AGP accepts (Android Studio's JBR); GraalVM's `jlink` breaks
  `JdkImageTransform`.
- The emulator's camera and microphone are virtual: clips there have AMR-NB audio (camera) or near-silence (mic).
  Sound quality needs a real phone.

## Generated and vendored files (never edit by hand)

| File | Regenerate with |
|---|---|
| `controller/ai-context/generated/*` | `npm run ai:sync` in `src/main/frontend` (also part of `./mvnw verify`) |
| `src/main/frontend/src/routeTree.gen.ts` | the TanStack Router Vite plugin (any `vite build` / `vite dev`) |
| `src/main/frontend/src/shared/components/ui/shadcn/` | the shadcn CLI with the `@react-aria` registry (`controller/README.md`) |
| `src/main/resources/static/` | the Vite build |

## When you add or change a feature

Update, in the same change:

1. **The contract:** `docs/protocol/ws-v1.md` (new types in a "Phase N additions" section, payload tables, error
   codes) or `transfers-v1.md`. Both sides must match it: `MessageType.java` + records with Bean Validation on the
   controller, `MessageTypes` + a `PhaseNProtocol.kt` in `:core:websocket` on the phone.
2. **The feature doc:** `docs/features/<feature>.md` (behaviour, limits, permissions, Android components).
3. **The phase plan:** `docs/plans/phase-N.md` (status, decisions, verification results, known limits).
4. **ai-context:** `topics/architecture.md` (packages, patterns), `topics/android-client.md` (module row, phase
   section, tests row), `topics/security.md` (anything an attacker or a phone could abuse), `topics/features.md`
   (the map), `topics/database-conventions.md` for schema rules. Then run `npm run ai:sync`.
5. **READMEs:** `controller/README.md` for new `dcontrol.*` properties, `client/README.md` for new permissions or
   setup, the root `README.md` status list.
6. **Tests:** controller integration tests with the `TestSocket` fake phone (`support/`), Vitest next to the
   web code, client unit tests behind the feature's seams (fake `RtcEngine`, fake providers, MockWebServer).

## Rules that hold everywhere

- **Phones are untrusted.** Validate everything a phone sends, stamp the authenticated device id, allow-list its
  error codes, check uploaded types by magic bytes, never by the phone's claim (`topics/security.md`).
- **No silent capture.** Screen, camera and microphone need the owner's consent on the phone for every session, a
  foreground service with a visible notification and Stop, and they never run while the app is hidden.
- **Personal data is relayed, not stored or logged.** Audit lines carry type, device and user only.
- **Database:** Flyway migrations follow `topics/database-conventions.md`; run `SchemaConventionIntegrationTest`.
- **Client modules:** features depend only on `:core:*`, never on each other.
- **Docs style:** short sentences, tables for reference data, no marketing words; keep `topics/` true to the code.

## Phases and branches

Work is delivered in phases. Each phase gets a plan, is built and verified (both gates, then an emulator run where
it touches the phone), and is confirmed by the project owner before the next starts. Each phase is one commit on
its own branch, cut from the previous phase's branch:

| Branch | Commit |
|---|---|
| `feature/phases-1-4` | Phases 1–4 |
| `feature/phase-5-camera` | Phase 5, remote camera |
| `feature/phase-6-mic` | Phase 6, remote microphone; pushed to `origin/v1` (github.com/ridoykj/dcontrol) |

Local-only files (`.claude/`, `client/local.properties`, `.env`) stay out of commits.
