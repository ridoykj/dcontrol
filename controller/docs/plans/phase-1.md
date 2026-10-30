# Phase 1 — Foundation (implemented)

Scope: monorepo scaffolding, auth (register, email verification, login, JWT + rotating refresh, password
reset), QR device pairing, a persistent phone→controller WebSocket with heartbeats and backoff, and
online/offline + battery in the web UI.

## Decisions

| Topic | Decision |
|---|---|
| Packages | Backend `com.dcontrol.controller`; Android `com.dcontrol.client` (debug: `.debug` suffix) |
| Header / events | One `T_EVENT` row per write transaction, created by `EventAwareTransactionManager`. Triggers read `dc.evt_key` / `dc.usr_key` |
| Email | Verification required before login. Forgot/reset password. Mailpit in dev |
| Email tokens | Signed JWTs (no table); reset tokens carry a password-hash fingerprint, which makes them single-use |
| UI kit | Official `@react-aria` shadcn registry (Tailwind 4); Jolly UI's registry was stale |
| TypeScript | 7.0 (native compiler), used for `tsc --noEmit` only |
| Tenancy | Hibernate `@TenantId` + resolver driven by the current `Actor` |
| FSM keys | Fixed UUIDv7-shaped literals shared by migrations and Java enums |
| Presence | In memory, persisted to unaudited `T_DEVICE_STATUS` on connect/disconnect and every ≤ 5 min |
| Device FGS type | `connectedDevice`. Play may require `specialUse` instead (see device-connection.md) |
| Android credentials | Keystore AES-GCM key, ciphertext in DataStore (`core/network`, shared by both features) |

## Versions

| Area | Version |
|---|---|
| Controller | Spring Boot 4.1.2 (Framework 7.0, Security 7.1, Hibernate 7.4, Jackson 3.1, Flyway 12.4), Java 25, Testcontainers 2.0.5, PostgreSQL 18, Mailpit 1.32 |
| Frontend | Node 26.13 (installed by Maven), React 19.3, Vite 8.3, TanStack Router 1.170 / Query 5.105, Tailwind 4.3, react-aria-components 1.22, zod 4.6, react-hook-form 7.91, Vitest 5.0, TypeScript 7.0 |
| Client | AGP 9.5, Gradle 9.8.2, Kotlin 2.4.21, KSP 2.3.12, Compose BOM 2026.09.00, Hilt 2.60.1, OkHttp 5.5.1, Retrofit 3.0, CameraX 1.6.2, ML Kit barcode 17.3.0, compile/target SDK 37, min SDK 26 |

## Migrations

`V1__core_library.sql` (T_EVENT, FSM catalog, `LIB_*`), `V2__account_user.sql`, `V3__refresh_token.sql`,
`V4__device_pairing.sql`.

## Deviations from the approved plan

- Jolly UI was replaced by the official React Aria registry (you approved this during scaffolding).
- `CredentialStore` and `DeviceInfoProvider` live in `client/core/network` rather than
  `feature/pairing/data`, because both features need them and features don't depend on each other.
- Unpairing on the phone only wipes local credentials. The device stays listed on the web until the user
  unpairs it there.
- `noUnusedLocals`/`noUnusedParameters` are off in `tsconfig.json`: the vendored React Aria components import
  `React` without using it, and vendored files must not be edited.

## Known limits

- Single controller instance only (in-memory registries, tickets and rate limits).
- `T_EVENT` has no retention job yet.
