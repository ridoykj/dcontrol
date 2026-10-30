# Database conventions (how they are implemented)

The rules come from the project brief; this note says where each one is enforced. Run
`SchemaConventionIntegrationTest` after every migration.

## Writing a migration

```sql
CALL LIB_begin_migration('5');            -- opens a T_EVENT so header triggers work inside Flyway

CREATE TABLE T_THING (
    ID_THING_KEY     UUID         NOT NULL,   -- header, in this order
    ID_THING_VER     BIGINT       NOT NULL,
    IS_ACTIVE        BOOLEAN      NOT NULL DEFAULT TRUE,
    DTT_MOD          TIMESTAMPTZ  NOT NULL,
    ID_USR_MOD       UUID         NOT NULL,
    ID_EVT_KEY       UUID         NOT NULL,
    -- ID_STATE_KEY, ID_ACTION_KEY        (stateful tables only)
    DTT_CREATED      TIMESTAMPTZ  NOT NULL,
    ID_USR_CREATED   UUID         NOT NULL,
    ID_ACCOUNT_KEY   UUID         NOT NULL,   -- foreign keys, account first
    TX_NAME          VARCHAR(64)  NOT NULL,   -- other columns
    CONSTRAINT PK_THING PRIMARY KEY (ID_THING_KEY),
    CONSTRAINT FK_THING_ACCOUNT FOREIGN KEY (ID_ACCOUNT_KEY) REFERENCES T_ACCOUNT (ID_ACCOUNT_KEY)
);
CALL LIB_attach_header('t_thing');
CALL LIB_attach_fsm('t_thing', 'THING');  -- stateful only; also adds fk_thing_state / fk_thing_action
CALL LIB_create_audit('t_thing');         -- when an audit trail is needed
CREATE UNIQUE INDEX UK_THING_NAME ON T_THING (ID_ACCOUNT_KEY, lower(TX_NAME)) WHERE IS_ACTIVE;
```

FSM catalog entries use fixed UUIDv7-shaped keys so Java enums can reference them:
`LIB_fsm_create`, `LIB_fsm_state`, `LIB_fsm_action`, `LIB_fsm_allow('THING', NULL | 'FROM', 'ACTION', 'TO')`.
Keys used so far: `…0001–0002` system rows, `…01xx` PAIRING, `…02xx` DEVICE, `…03xx` TRANSFER. Use `…04xx` for the next FSM.

## What the triggers do

| Trigger | Behaviour |
|---|---|
| `TR_<t>_evt` (`LIB_trg_header`) | Fills `dtt_mod`, `id_usr_mod`, `id_evt_key` (and the created columns on insert) from `dc.evt_key`/`dc.usr_key`. Raises `DC001` without event context and `DC002` when an UPDATE doesn't bump `id_<t>_ver` by exactly 1. |
| `TR_<t>_fsm` (`LIB_trg_fsm`) | Rejects state/action changes not in `T_FSM_TRANSITION` (`DC003`). |
| `TR_<t>_audit` (`LIB_trg_audit`) | Copies the row into `T_<t>_AUDIT` with `tx_audit_op` I/U/D and `dtt_audit`. A DELETE is stored with version + 1. |

Header columns `id_usr_mod`, `id_usr_created` and `id_evt_key` have **no FK constraints**: that would make
T_USER and T_EVENT depend on each other, and these are audit metadata. `T_EVENT` has no `TR_evt`; its
`id_evt_key` is its own primary key.

## Java side

- Entities extend `BaseEntity`, or `AccountScopedEntity` for business rows, and rename the inherited `id` and
  `version` with `@AttributeOverride` to `id_<t>_key` / `id_<t>_ver`. Every field has `@Column(name=…)`.
- Header columns are `insertable=false, updatable=false` and `@Generated`, so Hibernate reads back what the
  trigger wrote.
- Keys are `@UuidGenerator(style = VERSION_7)`; outside Hibernate use `Uuid7.generate()`.
- Stateful entities implement `StatefulEntity` and store their state and last action through an
  `FsmKeyConverter` subclass. Change state only with `XxxFsm.MACHINE.move(entity, action)`.
  `FsmAndHeaderIntegrationTest` checks the Java machines against the DB catalog.
- JPQL compares states with parameters (`d.state = :state`), never literals.
- Bulk JPQL/native UPDATEs must set `version = version + 1` (see `RefreshTokenRepository`).
- Physical deletes are limited to:
  - link rows;
  - expired pairing tokens and expired refresh tokens;
  - file transfers that ended more than `dcontrol.transfers.row-retention` (7 days) ago;
  - `T_DEVICE_LOCATION` rows, when the user forgets the location or unpairs the device. A hidden copy of a
    deleted location would be misleading.
  Everything else uses `is_active = false` via `CrudService.delete`, `?deleted=true` and `POST …/restore`.
- Business unique constraints are partial indexes `WHERE is_active`.
- Use `ScopedRepository.findByIdAndActive` rather than `findById`: derived JPQL queries always carry the tenant
  filter.

## Audited tables

`T_ACCOUNT`, `T_USER`, `T_DEVICE`. Three tables are deliberately not audited:
- `T_DEVICE_STATUS`: heartbeat writes.
- `T_FILE_TRANSFER`: operational, short-lived rows.
- `T_DEVICE_LOCATION` (V6): an audit table would keep every past position.
Any `ALTER` on an audited table must be repeated on its `_AUDIT` table; the schema test compares them
column by column.
