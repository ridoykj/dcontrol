package com.dcontrol.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.support.IntegrationTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks the database conventions (naming, header order, prefixes, constraints, triggers, audit parity).
 * Run after every migration.
 */
class SchemaConventionIntegrationTest extends IntegrationTest {

    private record Column(String name, String type) {
    }

    private static final Set<String> NOT_ACCOUNT_SCOPED = Set.of(
            "t_event", "t_account", "t_fsm", "t_fsm_state", "t_fsm_action", "t_fsm_transition");
    private static final List<String> AUDIT_SUFFIX = List.of("tx_audit_op", "dtt_audit");
    private static final Pattern TABLE_NAME = Pattern.compile("^t_[a-z0-9_]+$");

    private Map<String, List<Column>> tables;

    @BeforeEach
    void loadSchema() {
        tables = new LinkedHashMap<>();
        jdbc.query("""
                SELECT c.table_name, c.column_name, c.data_type
                FROM information_schema.columns c
                JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE'
                  AND c.table_name <> 'flyway_schema_history'
                ORDER BY c.table_name, c.ordinal_position""", rs -> {
            tables.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                    .add(new Column(rs.getString(2), rs.getString(3)));
        });
        assertThat(tables).isNotEmpty();
    }

    @Test
    void tableNamesAreUppercaseUnquotedTPrefixed() {
        assertThat(tables.keySet()).allSatisfy(table -> assertThat(table).matches(TABLE_NAME));
    }

    @Test
    void everyColumnUsesAKnownPrefixWithMatchingType() {
        List<String> violations = new ArrayList<>();
        tables.forEach((table, columns) -> columns.forEach(column -> {
            String expected = expectedType(column.name());
            if (expected == null) {
                violations.add(table + "." + column.name() + " has no known prefix");
            } else if (!expected.contains("|" + column.type() + "|")) {
                violations.add(table + "." + column.name() + " is " + column.type());
            }
        }));
        assertThat(violations).isEmpty();
    }

    @Test
    void headerColumnsComeFirstInOrder() {
        List<String> violations = new ArrayList<>();
        tables.forEach((table, columns) -> {
            List<String> names = columns.stream().map(Column::name).toList();
            String key = names.getFirst();
            if (!key.matches("^id_[a-z0-9_]+_key$")) {
                violations.add(table + " starts with " + key);
                return;
            }
            String t = key.substring(3, key.length() - 4);
            List<String> header = new ArrayList<>(List.of("id_" + t + "_key", "id_" + t + "_ver", "is_active",
                    "dtt_mod", "id_usr_mod"));
            if (!table.equals("t_event")) {
                header.add("id_evt_key");
            }
            if (isStateful(columns)) {
                header.addAll(List.of("id_state_key", "id_action_key"));
            }
            header.addAll(List.of("dtt_created", "id_usr_created"));
            if (!names.subList(0, Math.min(header.size(), names.size())).equals(header)) {
                violations.add(table + " header is " + names.subList(0, Math.min(header.size(), names.size())));
                return;
            }
            boolean scoped = !NOT_ACCOUNT_SCOPED.contains(baseTable(table));
            if (scoped && !"id_account_key".equals(names.get(header.size()))) {
                violations.add(table + " must have id_account_key right after the header");
            }
        });
        assertThat(violations).isEmpty();
    }

    @Test
    void foreignKeysPrecedeOtherColumns() {
        List<String> violations = new ArrayList<>();
        Map<String, Set<String>> fkColumns = new LinkedHashMap<>();
        jdbc.query("""
                SELECT rel.relname, att.attname
                FROM pg_constraint con
                JOIN pg_class rel ON rel.oid = con.conrelid
                JOIN pg_namespace ns ON ns.oid = rel.relnamespace
                JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = ANY (con.conkey)
                WHERE ns.nspname = 'public' AND con.contype = 'f'""", rs -> {
            fkColumns.computeIfAbsent(rs.getString(1), k -> new java.util.HashSet<>()).add(rs.getString(2));
        });
        tables.forEach((table, columns) -> {
            Set<String> fks = fkColumns.getOrDefault(table, Set.of());
            boolean seenOther = false;
            for (Column column : columns) {
                boolean headerOrKey = column.name().equals(columns.getFirst().name())
                        || Set.of("id_state_key", "id_action_key").contains(column.name())
                        || column.name().matches("^(id_[a-z0-9_]+_ver|is_active|dtt_mod|id_usr_mod|id_evt_key|dtt_created|id_usr_created)$");
                if (headerOrKey) {
                    continue;
                }
                if (fks.contains(column.name())) {
                    if (seenOther) {
                        violations.add(table + "." + column.name() + " is a foreign key after other columns");
                    }
                } else {
                    seenOther = true;
                }
            }
        });
        assertThat(violations).isEmpty();
    }

    @Test
    void constraintsAndIndexesAreNamed() {
        List<String> violations = new ArrayList<>();
        jdbc.query("""
                SELECT rel.relname, con.conname, con.contype
                FROM pg_constraint con
                JOIN pg_class rel ON rel.oid = con.conrelid
                JOIN pg_namespace ns ON ns.oid = rel.relnamespace
                WHERE ns.nspname = 'public' AND con.contype IN ('p', 'u', 'f', 'c')
                  AND rel.relname <> 'flyway_schema_history'""", rs -> {
            String table = rs.getString(1);
            String name = rs.getString(2);
            String prefix = switch (rs.getString(3)) {
                case "p" -> "pk_";
                case "u" -> "uk_";
                case "f" -> "fk_";
                default -> "ck_";
            };
            String expectedStart = prefix + table.substring(2);
            boolean exact = prefix.equals("pk_") ? name.equals(expectedStart) : name.startsWith(expectedStart);
            if (!exact) {
                violations.add(table + ": constraint " + name + " should start with " + expectedStart);
            }
        });
        jdbc.query("""
                SELECT tablename, indexname FROM pg_indexes
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'""", rs -> {
            String table = rs.getString(1);
            String name = rs.getString(2);
            String suffix = table.substring(2);
            if (!(name.equals("pk_" + suffix) || name.startsWith("uk_" + suffix) || name.startsWith("idx_" + suffix))) {
                violations.add(table + ": index " + name);
            }
        });
        assertThat(violations).isEmpty();
    }

    @Test
    void headerFsmAndAuditTriggersAreAttached() {
        Set<String> triggers = new java.util.HashSet<>(jdbc.queryForList("""
                SELECT event_object_table || ':' || trigger_name FROM information_schema.triggers
                WHERE trigger_schema = 'public'""", String.class));
        List<String> violations = new ArrayList<>();
        tables.forEach((table, columns) -> {
            if (table.equals("t_event") || table.endsWith("_audit")) {
                return;
            }
            if (!triggers.contains(table + ":tr_" + table + "_evt")) {
                violations.add(table + " lacks tr_" + table + "_evt");
            }
            if (isStateful(columns) &&!triggers.contains(table + ":tr_" + table + "_fsm")) {
                violations.add(table + " lacks tr_" + table + "_fsm");
            }
            if (tables.containsKey(table + "_audit") && !triggers.contains(table + ":tr_" + table + "_audit")) {
                violations.add(table + " lacks tr_" + table + "_audit");
            }
        });
        assertThat(violations).isEmpty();
    }

    @Test
    void auditTablesMirrorTheirTable() {
        tables.keySet().stream().filter(t -> t.endsWith("_audit")).forEach(audit -> {
            String base = audit.substring(0, audit.length() - "_audit".length());
            assertThat(tables).containsKey(base);
            List<Column> auditColumns = tables.get(audit);
            List<Column> expected = new ArrayList<>(tables.get(base));
            assertThat(auditColumns.subList(0, auditColumns.size() - 2)).as(audit).isEqualTo(expected);
            assertThat(auditColumns.subList(auditColumns.size() - 2, auditColumns.size()))
                    .extracting(Column::name).containsExactlyElementsOf(AUDIT_SUFFIX);
        });
    }

    @Test
    void auditedTablesInPhaseOne() {
        assertThat(tables.keySet()).contains("t_account_audit", "t_user_audit", "t_device_audit");
    }

    /** Has FSM header columns; T_FSM_STATE itself is excluded because id_state_key is its primary key. */
    private static boolean isStateful(List<Column> columns) {
        return !columns.getFirst().name().equals("id_state_key")
                && columns.stream().anyMatch(c -> c.name().equals("id_state_key"));
    }

    private static String baseTable(String table) {
        return table.endsWith("_audit") ? table.substring(0, table.length() - "_audit".length()) : table;
    }

    /** Allowed information_schema data types per prefix, as {@code |type|type|}. */
    private static String expectedType(String column) {
        if (column.matches("^id_[a-z0-9_]+_key$")) {
            return "|uuid|";
        }
        if (column.matches("^id_[a-z0-9_]+_ver$")) {
            return "|bigint|";
        }
        if (column.equals("id_usr_mod") || column.equals("id_usr_created")) {
            return "|uuid|";
        }
        if (column.startsWith("tx_")) {
            return "|text|character varying|";
        }
        if (column.startsWith("is_")) {
            return "|boolean|";
        }
        if (column.startsWith("dtt_")) {
            return "|timestamp with time zone|";
        }
        if (column.startsWith("dt_")) {
            return "|date|";
        }
        if (column.startsWith("ct_")) {
            return "|integer|bigint|smallint|";
        }
        if (column.startsWith("dec_")) {
            return "|numeric|";
        }
        if (column.startsWith("js_")) {
            return "|jsonb|";
        }
        return null;
    }
}
