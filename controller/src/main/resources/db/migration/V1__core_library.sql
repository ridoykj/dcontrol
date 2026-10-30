-- Core library: event log, header/audit/FSM triggers and the LIB_* helpers every later migration uses.
-- Identifiers are written in UPPERCASE and never quoted; PostgreSQL folds them to lowercase.

-- ---------------------------------------------------------------------------------------------
-- T_EVENT: one row per write transaction. Java inserts it at transaction begin and publishes its key
-- through the transaction-local settings dc.evt_key / dc.usr_key, which TR_<t>_evt reads.
-- The header column id_evt_key of T_EVENT is its own primary key.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE T_EVENT (
    ID_EVT_KEY       UUID         NOT NULL,
    ID_EVT_VER       BIGINT       NOT NULL,
    IS_ACTIVE        BOOLEAN      NOT NULL DEFAULT TRUE,
    DTT_MOD          TIMESTAMPTZ  NOT NULL,
    ID_USR_MOD       UUID         NOT NULL,
    DTT_CREATED      TIMESTAMPTZ  NOT NULL,
    ID_USR_CREATED   UUID         NOT NULL,
    ID_ACCOUNT_KEY   UUID         NOT NULL,
    TX_TYPE          VARCHAR(16)  NOT NULL,
    TX_ACTOR         VARCHAR(80)  NOT NULL,
    TX_REQUEST_ID    VARCHAR(64),
    CONSTRAINT PK_EVENT PRIMARY KEY (ID_EVT_KEY),
    CONSTRAINT CK_EVENT_TYPE CHECK (TX_TYPE IN ('HTTP', 'WS_DEVICE', 'WS_WEB', 'JOB', 'MIGRATION', 'SYSTEM'))
);
CREATE INDEX IDX_EVENT_ACCOUNT ON T_EVENT (ID_ACCOUNT_KEY, DTT_CREATED);

-- Opens an event for a migration so header triggers can run inside Flyway's transaction.
CREATE PROCEDURE LIB_begin_migration(p_version TEXT)
LANGUAGE plpgsql AS $$
DECLARE
    v_system UUID := '01900000-0000-7000-8000-000000000002';
    v_account UUID := '01900000-0000-7000-8000-000000000001';
    v_evt UUID := uuidv7();
BEGIN
    INSERT INTO T_EVENT (ID_EVT_KEY, ID_EVT_VER, IS_ACTIVE, DTT_MOD, ID_USR_MOD, DTT_CREATED, ID_USR_CREATED,
                         ID_ACCOUNT_KEY, TX_TYPE, TX_ACTOR, TX_REQUEST_ID)
    VALUES (v_evt, 0, TRUE, now(), v_system, now(), v_system, v_account, 'MIGRATION', 'flyway', p_version);
    PERFORM set_config('dc.evt_key', v_evt::TEXT, TRUE);
    PERFORM set_config('dc.usr_key', v_system::TEXT, TRUE);
END;
$$;

-- Returns the <t> of id_<t>_key for a table, read from its primary key.
CREATE FUNCTION LIB_key_name(p_table TEXT) RETURNS TEXT
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_col TEXT;
BEGIN
    SELECT a.attname INTO v_col
    FROM pg_index i
    JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
    WHERE i.indrelid = lower(p_table)::regclass AND i.indisprimary;
    IF v_col IS NULL OR v_col !~ '^id_[a-z0-9_]+_key$' THEN
        RAISE EXCEPTION 'Table % has no id_<t>_key primary key', p_table;
    END IF;
    RETURN substring(v_col FROM '^id_(.+)_key$');
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- Header trigger: fills dtt_mod/id_usr_mod/id_evt_key (and dtt_created/id_usr_created on insert)
-- from the event context and requires every UPDATE to bump id_<t>_ver by exactly one.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION LIB_trg_header() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_evt TEXT := current_setting('dc.evt_key', TRUE);
    v_usr TEXT := current_setting('dc.usr_key', TRUE);
    v_ver TEXT := TG_ARGV[0];
BEGIN
    IF v_evt IS NULL OR v_evt = '' OR v_usr IS NULL OR v_usr = '' THEN
        RAISE EXCEPTION 'No event context for write to %', TG_TABLE_NAME USING ERRCODE = 'DC001';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF (to_jsonb(NEW) ->> v_ver)::BIGINT IS DISTINCT FROM (to_jsonb(OLD) ->> v_ver)::BIGINT + 1 THEN
            RAISE EXCEPTION '% must be incremented by one when updating %', v_ver, TG_TABLE_NAME
                USING ERRCODE = 'DC002';
        END IF;
        NEW.dtt_created := OLD.dtt_created;
        NEW.id_usr_created := OLD.id_usr_created;
    ELSE
        NEW.dtt_created := now();
        NEW.id_usr_created := v_usr::UUID;
    END IF;
    NEW.dtt_mod := now();
    NEW.id_usr_mod := v_usr::UUID;
    NEW.id_evt_key := v_evt::UUID;
    RETURN NEW;
END;
$$;

CREATE PROCEDURE LIB_attach_header(p_table TEXT)
LANGUAGE plpgsql AS $$
DECLARE
    v_table TEXT := lower(p_table);
    v_name TEXT := LIB_key_name(p_table);
BEGIN
    EXECUTE format('CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION lib_trg_header(%L)',
                   'tr_' || v_table || '_evt', v_table, 'id_' || v_name || '_ver');
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- Audit: T_<NAME>_AUDIT copies the table plus tx_audit_op and dtt_audit, keyed by (key, version).
-- A DELETE is recorded with version + 1 so it never collides with the last insert/update row.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION LIB_trg_audit() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_audit TEXT := TG_TABLE_NAME || '_audit';
    v_ver TEXT := TG_ARGV[0];
    v_row JSONB;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_row := to_jsonb(OLD)
                 || jsonb_build_object(v_ver, (to_jsonb(OLD) ->> v_ver)::BIGINT + 1, 'tx_audit_op', 'D');
    ELSE
        v_row := to_jsonb(NEW) || jsonb_build_object('tx_audit_op', left(TG_OP, 1));
    END IF;
    v_row := v_row || jsonb_build_object('dtt_audit', now());
    EXECUTE format('INSERT INTO %I SELECT (jsonb_populate_record(NULL::%I, $1)).*', v_audit, v_audit) USING v_row;
    RETURN NULL;
END;
$$;

CREATE PROCEDURE LIB_create_audit(p_table TEXT)
LANGUAGE plpgsql AS $$
DECLARE
    v_table TEXT := lower(p_table);
    v_short TEXT := substring(lower(p_table) FROM '^t_(.+)$');
    v_name TEXT := LIB_key_name(p_table);
    v_audit TEXT := lower(p_table) || '_audit';
BEGIN
    EXECUTE format('CREATE TABLE %I (LIKE %I INCLUDING DEFAULTS)', v_audit, v_table);
    EXECUTE format('ALTER TABLE %I ADD COLUMN tx_audit_op VARCHAR(1) NOT NULL, ADD COLUMN dtt_audit TIMESTAMPTZ NOT NULL',
                   v_audit);
    EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I PRIMARY KEY (%I, %I)',
                   v_audit, 'pk_' || v_short || '_audit', 'id_' || v_name || '_key', 'id_' || v_name || '_ver');
    EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (tx_audit_op IN (''I'', ''U'', ''D''))',
                   v_audit, 'ck_' || v_short || '_audit_op');
    EXECUTE format('CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION lib_trg_audit(%L)',
                   'tr_' || v_table || '_audit', v_table, 'id_' || v_name || '_ver');
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- FSM catalog
-- ---------------------------------------------------------------------------------------------
CREATE TABLE T_FSM (
    ID_FSM_KEY       UUID         NOT NULL,
    ID_FSM_VER       BIGINT       NOT NULL,
    IS_ACTIVE        BOOLEAN      NOT NULL DEFAULT TRUE,
    DTT_MOD          TIMESTAMPTZ  NOT NULL,
    ID_USR_MOD       UUID         NOT NULL,
    ID_EVT_KEY       UUID         NOT NULL,
    DTT_CREATED      TIMESTAMPTZ  NOT NULL,
    ID_USR_CREATED   UUID         NOT NULL,
    TX_CODE          VARCHAR(32)  NOT NULL,
    CONSTRAINT PK_FSM PRIMARY KEY (ID_FSM_KEY)
);
CALL LIB_attach_header('t_fsm');
CREATE UNIQUE INDEX UK_FSM_CODE ON T_FSM (TX_CODE) WHERE IS_ACTIVE;

CREATE TABLE T_FSM_STATE (
    ID_STATE_KEY     UUID         NOT NULL,
    ID_STATE_VER     BIGINT       NOT NULL,
    IS_ACTIVE        BOOLEAN      NOT NULL DEFAULT TRUE,
    DTT_MOD          TIMESTAMPTZ  NOT NULL,
    ID_USR_MOD       UUID         NOT NULL,
    ID_EVT_KEY       UUID         NOT NULL,
    DTT_CREATED      TIMESTAMPTZ  NOT NULL,
    ID_USR_CREATED   UUID         NOT NULL,
    ID_FSM_KEY       UUID         NOT NULL,
    TX_CODE          VARCHAR(32)  NOT NULL,
    CONSTRAINT PK_FSM_STATE PRIMARY KEY (ID_STATE_KEY),
    CONSTRAINT FK_FSM_STATE_FSM FOREIGN KEY (ID_FSM_KEY) REFERENCES T_FSM (ID_FSM_KEY)
);
CALL LIB_attach_header('t_fsm_state');
CREATE UNIQUE INDEX UK_FSM_STATE_CODE ON T_FSM_STATE (ID_FSM_KEY, TX_CODE) WHERE IS_ACTIVE;

CREATE TABLE T_FSM_ACTION (
    ID_ACTION_KEY    UUID         NOT NULL,
    ID_ACTION_VER    BIGINT       NOT NULL,
    IS_ACTIVE        BOOLEAN      NOT NULL DEFAULT TRUE,
    DTT_MOD          TIMESTAMPTZ  NOT NULL,
    ID_USR_MOD       UUID         NOT NULL,
    ID_EVT_KEY       UUID         NOT NULL,
    DTT_CREATED      TIMESTAMPTZ  NOT NULL,
    ID_USR_CREATED   UUID         NOT NULL,
    ID_FSM_KEY       UUID         NOT NULL,
    TX_CODE          VARCHAR(32)  NOT NULL,
    CONSTRAINT PK_FSM_ACTION PRIMARY KEY (ID_ACTION_KEY),
    CONSTRAINT FK_FSM_ACTION_FSM FOREIGN KEY (ID_FSM_KEY) REFERENCES T_FSM (ID_FSM_KEY)
);
CALL LIB_attach_header('t_fsm_action');
CREATE UNIQUE INDEX UK_FSM_ACTION_CODE ON T_FSM_ACTION (ID_FSM_KEY, TX_CODE) WHERE IS_ACTIVE;

-- ID_STATE_FROM_KEY is NULL for the creating transition.
CREATE TABLE T_FSM_TRANSITION (
    ID_TRANSITION_KEY  UUID         NOT NULL,
    ID_TRANSITION_VER  BIGINT       NOT NULL,
    IS_ACTIVE          BOOLEAN      NOT NULL DEFAULT TRUE,
    DTT_MOD            TIMESTAMPTZ  NOT NULL,
    ID_USR_MOD         UUID         NOT NULL,
    ID_EVT_KEY         UUID         NOT NULL,
    DTT_CREATED        TIMESTAMPTZ  NOT NULL,
    ID_USR_CREATED     UUID         NOT NULL,
    ID_FSM_KEY         UUID         NOT NULL,
    ID_STATE_FROM_KEY  UUID,
    ID_ACTION_KEY      UUID         NOT NULL,
    ID_STATE_TO_KEY    UUID         NOT NULL,
    CONSTRAINT PK_FSM_TRANSITION PRIMARY KEY (ID_TRANSITION_KEY),
    CONSTRAINT FK_FSM_TRANSITION_FSM FOREIGN KEY (ID_FSM_KEY) REFERENCES T_FSM (ID_FSM_KEY),
    CONSTRAINT FK_FSM_TRANSITION_STATE_FROM FOREIGN KEY (ID_STATE_FROM_KEY) REFERENCES T_FSM_STATE (ID_STATE_KEY),
    CONSTRAINT FK_FSM_TRANSITION_ACTION FOREIGN KEY (ID_ACTION_KEY) REFERENCES T_FSM_ACTION (ID_ACTION_KEY),
    CONSTRAINT FK_FSM_TRANSITION_STATE_TO FOREIGN KEY (ID_STATE_TO_KEY) REFERENCES T_FSM_STATE (ID_STATE_KEY)
);
CALL LIB_attach_header('t_fsm_transition');
CREATE UNIQUE INDEX UK_FSM_TRANSITION ON T_FSM_TRANSITION (ID_FSM_KEY, ID_STATE_FROM_KEY, ID_ACTION_KEY)
    NULLS NOT DISTINCT WHERE IS_ACTIVE;

CREATE PROCEDURE LIB_fsm_create(p_fsm TEXT, p_key UUID)
LANGUAGE sql AS $$
    INSERT INTO T_FSM (ID_FSM_KEY, ID_FSM_VER, TX_CODE) VALUES (p_key, 0, p_fsm);
$$;

CREATE PROCEDURE LIB_fsm_state(p_fsm TEXT, p_code TEXT, p_key UUID)
LANGUAGE sql AS $$
    INSERT INTO T_FSM_STATE (ID_STATE_KEY, ID_STATE_VER, ID_FSM_KEY, TX_CODE)
    SELECT p_key, 0, ID_FSM_KEY, p_code FROM T_FSM WHERE TX_CODE = p_fsm AND IS_ACTIVE;
$$;

CREATE PROCEDURE LIB_fsm_action(p_fsm TEXT, p_code TEXT, p_key UUID)
LANGUAGE sql AS $$
    INSERT INTO T_FSM_ACTION (ID_ACTION_KEY, ID_ACTION_VER, ID_FSM_KEY, TX_CODE)
    SELECT p_key, 0, ID_FSM_KEY, p_code FROM T_FSM WHERE TX_CODE = p_fsm AND IS_ACTIVE;
$$;

-- Allows p_from --p_action--> p_to in FSM p_fsm. p_from NULL means "creating transition".
CREATE PROCEDURE LIB_fsm_allow(p_fsm TEXT, p_from TEXT, p_action TEXT, p_to TEXT)
LANGUAGE plpgsql AS $$
DECLARE
    v_fsm UUID;
    v_from UUID;
    v_action UUID;
    v_to UUID;
BEGIN
    SELECT ID_FSM_KEY INTO STRICT v_fsm FROM T_FSM WHERE TX_CODE = p_fsm AND IS_ACTIVE;
    IF p_from IS NOT NULL THEN
        SELECT ID_STATE_KEY INTO STRICT v_from FROM T_FSM_STATE
        WHERE ID_FSM_KEY = v_fsm AND TX_CODE = p_from AND IS_ACTIVE;
    END IF;
    SELECT ID_ACTION_KEY INTO STRICT v_action FROM T_FSM_ACTION
    WHERE ID_FSM_KEY = v_fsm AND TX_CODE = p_action AND IS_ACTIVE;
    SELECT ID_STATE_KEY INTO STRICT v_to FROM T_FSM_STATE
    WHERE ID_FSM_KEY = v_fsm AND TX_CODE = p_to AND IS_ACTIVE;
    INSERT INTO T_FSM_TRANSITION (ID_TRANSITION_KEY, ID_TRANSITION_VER, ID_FSM_KEY, ID_STATE_FROM_KEY,
                                  ID_ACTION_KEY, ID_STATE_TO_KEY)
    VALUES (uuidv7(), 0, v_fsm, v_from, v_action, v_to);
END;
$$;

-- FSM trigger: rejects any state/action change that is not a registered transition.
CREATE FUNCTION LIB_trg_fsm() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_fsm UUID := TG_ARGV[0]::UUID;
    v_from UUID;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.id_state_key IS NOT DISTINCT FROM OLD.id_state_key
           AND NEW.id_action_key IS NOT DISTINCT FROM OLD.id_action_key THEN
            RETURN NEW;
        END IF;
        v_from := OLD.id_state_key;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM t_fsm_transition t
                   WHERE t.id_fsm_key = v_fsm
                     AND t.id_state_from_key IS NOT DISTINCT FROM v_from
                     AND t.id_action_key = NEW.id_action_key
                     AND t.id_state_to_key = NEW.id_state_key
                     AND t.is_active) THEN
        RAISE EXCEPTION 'Transition % --%--> % is not allowed on %',
            v_from, NEW.id_action_key, NEW.id_state_key, TG_TABLE_NAME USING ERRCODE = 'DC003';
    END IF;
    RETURN NEW;
END;
$$;

CREATE PROCEDURE LIB_attach_fsm(p_table TEXT, p_fsm TEXT)
LANGUAGE plpgsql AS $$
DECLARE
    v_table TEXT := lower(p_table);
    v_short TEXT := substring(lower(p_table) FROM '^t_(.+)$');
    v_fsm UUID;
BEGIN
    SELECT ID_FSM_KEY INTO STRICT v_fsm FROM T_FSM WHERE TX_CODE = p_fsm AND IS_ACTIVE;
    EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (id_state_key) REFERENCES t_fsm_state (id_state_key)',
                   v_table, 'fk_' || v_short || '_state');
    EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (id_action_key) REFERENCES t_fsm_action (id_action_key)',
                   v_table, 'fk_' || v_short || '_action');
    EXECUTE format('CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION lib_trg_fsm(%L)',
                   'tr_' || v_table || '_fsm', v_table, v_fsm);
END;
$$;
