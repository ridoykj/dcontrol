# ai-context

The knowledge base for AI coding agents (and people) working on dcontrol. Read this before changing code: it says
where things are and which rules the code relies on. The root `AGENTS.md` points here.

## Layout

| Path | What | Who writes it |
|---|---|---|
| `topics/` | Hand-written notes: architecture, rules, patterns, decisions | Whoever changes the code, in the same change |
| `generated/` | Facts extracted from source by `sync.mjs` | `npm run ai:sync` in `src/main/frontend`, also run by `./mvnw verify`. Never edit |
| `sync.mjs` | The generator (Node, no dependencies) | Extend it when a new kind of fact can be read from source |

## Topics

| File | Read it when you… |
|---|---|
| [topics/workflow.md](topics/workflow.md) | build, test or run anything; finish a change (docs checklist); commit |
| [topics/features.md](topics/features.md) | need to find a feature's code on the controller, the web and the phone, or its permissions |
| [topics/architecture.md](topics/architecture.md) | touch the controller or the web app: packages, request flow, actor/tenancy, the patterns for adding phone features |
| [topics/android-client.md](topics/android-client.md) | touch `client/`: module map, conventions, command plumbing, per-feature behaviour, tests |
| [topics/security.md](topics/security.md) | add an endpoint, a message type, an upload, or anything that handles phone data |
| [topics/database-conventions.md](topics/database-conventions.md) | write a Flyway migration or an entity |

## Generated

| File | Source |
|---|---|
| [generated/ws-message-types.md](generated/ws-message-types.md) | `MessageType.java` |
| [generated/rest-endpoints.md](generated/rest-endpoints.md) | `@RestController` mappings |
| [generated/frontend-routes.md](generated/frontend-routes.md) | `src/main/frontend/src/routes` |
| [generated/schema.md](generated/schema.md) | Flyway migrations (tables, FSM transitions, library procedures) |
| [generated/config-properties.md](generated/config-properties.md) | `dcontrol.*` in `application.yaml` |
| [generated/android-modules.md](generated/android-modules.md) | `client/settings.gradle.kts` and module build files |

## Other sources of truth

- Wire contract: `docs/protocol/ws-v1.md`, `docs/protocol/transfers-v1.md`.
- Feature behaviour for users: `docs/features/*.md`.
- Why things are the way they are: `docs/plans/phase-N.md` (decisions, verification, known limits).
- Setup: the root, `controller/` and `client/` READMEs.

When the code and a topic disagree, the code wins. Fix the topic in the same change.
