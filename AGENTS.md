# dcontrol: notes for AI coding agents

dcontrol manages an Android phone from a browser, AirDroid-style. It is a monorepo:

- `controller/`: Spring Boot 4.1 (Java 25) + React SPA (TypeScript, TanStack Router/Query, React Aria). REST API,
  WebSocket relay between browsers and phones, PostgreSQL 18 with Flyway.
- `client/`: the Android app (Kotlin, Compose, Hilt, Room 3, CameraX, WebRTC), package `com.dcontrol.client`.

## Start here

The knowledge base is [controller/ai-context/](controller/ai-context/README.md). Read its README, then the topic
for the area you touch:

- [workflow.md](controller/ai-context/topics/workflow.md): build/test gates, running locally, the docs checklist
  for every change, branches.
- [features.md](controller/ai-context/topics/features.md): where each feature lives on the controller, the web
  and the phone.
- [architecture.md](controller/ai-context/topics/architecture.md), [android-client.md](controller/ai-context/topics/android-client.md),
  [security.md](controller/ai-context/topics/security.md), [database-conventions.md](controller/ai-context/topics/database-conventions.md).
- `controller/ai-context/generated/`: message types, endpoints, routes, schema, config properties, Android
  modules, regenerated from source on every build.

## Must-follow rules

- Gates before calling anything done: `cd controller && ./mvnw verify` and
  `cd client && ./gradlew testDebugUnitTest lintDebug assembleDebug`.
- The wire contract is `controller/docs/protocol/ws-v1.md`; change it together with both sides.
- Phones are untrusted input. Personal data is relayed, never stored or logged.
- Screen, camera and microphone always need the owner's consent on the phone and a visible foreground
  notification. Never add a silent, hidden or automatic capture mode.
- Never edit generated files: `ai-context/generated/*`, `routeTree.gen.ts`, `src/main/resources/static/`, the
  vendored `shared/components/ui/shadcn/`.
- Update the docs and ai-context topics in the same change as the code (checklist in `workflow.md`).
- Ask instead of guessing when a requirement or a library version is unclear. Work goes phase by phase, and the
  project owner confirms each phase.
