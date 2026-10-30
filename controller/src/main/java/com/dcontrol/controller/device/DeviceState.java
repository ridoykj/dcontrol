package com.dcontrol.controller.device;

import com.dcontrol.controller.common.fsm.FsmKey;
import java.util.UUID;

/** States of FSM {@code DEVICE}; keys match V4__device_pairing.sql. */
public enum DeviceState implements FsmKey {
    ACTIVE("01900000-0000-7000-8000-000000000201"),
    REVOKED("01900000-0000-7000-8000-000000000202");

    private final UUID key;

    DeviceState(String key) {
        this.key = UUID.fromString(key);
    }

    @Override
    public UUID key() {
        return key;
    }

    public static class Converter extends com.dcontrol.controller.common.fsm.FsmKeyConverter<DeviceState> {
        public Converter() {
            super(DeviceState.class);
        }
    }
}
