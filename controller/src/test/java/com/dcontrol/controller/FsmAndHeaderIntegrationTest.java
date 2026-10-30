package com.dcontrol.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.context.EventChannel;
import com.dcontrol.controller.common.fsm.FsmKey;
import com.dcontrol.controller.common.fsm.StateMachine;
import com.dcontrol.controller.common.persistence.ActorTx;
import com.dcontrol.controller.device.DeviceAction;
import com.dcontrol.controller.device.DeviceFsm;
import com.dcontrol.controller.device.DeviceState;
import com.dcontrol.controller.pairing.PairingFsm;
import com.dcontrol.controller.transfer.TransferFsm;
import com.dcontrol.controller.support.IntegrationTest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/** FSM catalog parity and the behaviour of the header, FSM and audit triggers. */
class FsmAndHeaderIntegrationTest extends IntegrationTest {

    @Autowired
    ActorTx actorTx;

    static Stream<StateMachine<?, ?>> machines() {
        return Stream.of(DeviceFsm.MACHINE, PairingFsm.MACHINE, TransferFsm.MACHINE);
    }

    @ParameterizedTest
    @MethodSource("machines")
    void javaMachineMatchesDatabaseCatalog(StateMachine<?, ?> machine) {
        assertThat(jdbc.queryForObject("SELECT tx_code FROM t_fsm WHERE id_fsm_key = ?", String.class, machine.key()))
                .isEqualTo(machine.code());
        assertThat(catalog("t_fsm_state", "id_state_key", machine.key())).isEqualTo(keys(machine.stateType()));
        assertThat(catalog("t_fsm_action", "id_action_key", machine.key())).isEqualTo(keys(machine.actionType()));

        Set<List<UUID>> dbTransitions = new HashSet<>();
        jdbc.query("""
                SELECT id_state_from_key, id_action_key, id_state_to_key FROM t_fsm_transition
                WHERE id_fsm_key = ? AND is_active""", rs -> {
            dbTransitions.add(java.util.Arrays.asList(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class)));
        }, machine.key());
        Set<List<UUID>> javaTransitions = new HashSet<>();
        machine.transitions().forEach(t -> javaTransitions.add(java.util.Arrays.asList(
                t.from() == null ? null : ((FsmKey) t.from()).key(), ((FsmKey) t.action()).key(),
                ((FsmKey) t.to()).key())));
        assertThat(dbTransitions).isEqualTo(javaTransitions);
    }

    @Test
    void headerIsFilledFromTheEventOfTheWritingUser() {
        UserSession session = newUser("header");
        PairedDevice device = pairDevice(session, "Header phone");

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT d.id_usr_created, d.id_usr_mod, e.tx_actor, e.tx_type, e.id_account_key
                FROM t_device d JOIN t_event e ON e.id_evt_key = d.id_evt_key
                WHERE d.id_device_key = ?""", device.deviceId());
        assertThat(row.get("id_usr_created")).isEqualTo(session.userId());
        assertThat(row.get("id_usr_mod")).isEqualTo(session.userId());
        assertThat(row.get("tx_actor")).isEqualTo("user:" + session.userId());
        assertThat(row.get("tx_type")).isEqualTo("HTTP");
        assertThat(row.get("id_account_key")).isEqualTo(session.accountId());
    }

    @Test
    void auditRecordsInsertAndUpdateByVersion() {
        UserSession session = newUser("audit");
        PairedDevice device = pairDevice(session, "Audit phone");
        assertThat(api.patch("/api/devices/" + device.deviceId(), Map.of("name", "Renamed"),
                session.accessToken()).status()).isEqualTo(200);

        List<Map<String, Object>> audit = jdbc.queryForList("""
                SELECT id_device_ver, tx_audit_op, tx_name FROM t_device_audit
                WHERE id_device_key = ? ORDER BY id_device_ver""", device.deviceId());
        assertThat(audit).extracting(r -> r.get("tx_audit_op")).containsExactly("I", "U");
        assertThat(audit).extracting(r -> r.get("tx_name")).containsExactly("Audit phone", "Renamed");
        assertThat(audit).extracting(r -> ((Number) r.get("id_device_ver")).longValue()).containsExactly(0L, 1L);
    }

    @Test
    void fsmTriggerRejectsUnregisteredTransition() {
        PairedDevice device = pairDevice(newUser("fsm"), "FSM phone");
        assertThatThrownBy(() -> actorTx.write(Actor.system("test", EventChannel.SYSTEM), () -> jdbc.update("""
                UPDATE t_device SET id_state_key = ?, id_action_key = ?, id_device_ver = id_device_ver + 1
                WHERE id_device_key = ?""", DeviceState.REVOKED.key(), DeviceAction.PAIR.key(), device.deviceId())))
                .hasMessageContaining("is not allowed");
    }

    @Test
    void fsmTriggerAcceptsRegisteredTransition() {
        PairedDevice device = pairDevice(newUser("fsm-ok"), "FSM phone");
        int updated = actorTx.write(Actor.system("test", EventChannel.SYSTEM), () -> jdbc.update("""
                UPDATE t_device SET id_state_key = ?, id_action_key = ?, id_device_ver = id_device_ver + 1
                WHERE id_device_key = ?""", DeviceState.REVOKED.key(), DeviceAction.REVOKE.key(), device.deviceId()));
        assertThat(updated).isEqualTo(1);
    }

    @Test
    void headerTriggerRequiresVersionBump() {
        PairedDevice device = pairDevice(newUser("version"), "Version phone");
        assertThatThrownBy(() -> actorTx.write(Actor.system("test", EventChannel.SYSTEM), () -> jdbc.update(
                "UPDATE t_device SET tx_name = 'x' WHERE id_device_key = ?", device.deviceId())))
                .hasMessageContaining("must be incremented");
    }

    @Test
    void headerTriggerRequiresEventContext() {
        PairedDevice device = pairDevice(newUser("noevent"), "No event phone");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE t_device SET tx_name = 'x', id_device_ver = id_device_ver + 1 WHERE id_device_key = ?",
                device.deviceId()))
                .hasMessageContaining("No event context");
    }

    private Set<Map.Entry<UUID, String>> catalog(String table, String keyColumn, UUID fsmKey) {
        Set<Map.Entry<UUID, String>> rows = new HashSet<>();
        jdbc.query("SELECT " + keyColumn + ", tx_code FROM " + table + " WHERE id_fsm_key = ? AND is_active",
                rs -> {
                    rows.add(Map.entry(rs.getObject(1, UUID.class), rs.getString(2)));
                }, fsmKey);
        return rows;
    }

    private static Set<Map.Entry<UUID, String>> keys(Class<?> enumType) {
        Set<Map.Entry<UUID, String>> rows = new HashSet<>();
        for (Object constant : enumType.getEnumConstants()) {
            FsmKey key = (FsmKey) constant;
            rows.add(Map.entry(key.key(), key.name()));
        }
        return rows;
    }
}
