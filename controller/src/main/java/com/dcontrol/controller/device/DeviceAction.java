package com.dcontrol.controller.device;

import com.dcontrol.controller.common.fsm.FsmKey;
import java.util.UUID;

/** Actions of FSM {@code DEVICE}; keys match V4__device_pairing.sql. */
public enum DeviceAction implements FsmKey {
    PAIR("01900000-0000-7000-8000-000000000251"),
    REVOKE("01900000-0000-7000-8000-000000000252"),
    REINSTATE("01900000-0000-7000-8000-000000000253");

    private final UUID key;

    DeviceAction(String key) {
        this.key = UUID.fromString(key);
    }

    @Override
    public UUID key() {
        return key;
    }

    public static class Converter extends com.dcontrol.controller.common.fsm.FsmKeyConverter<DeviceAction> {
        public Converter() {
            super(DeviceAction.class);
        }
    }
}
