package com.dcontrol.controller.pairing;

import com.dcontrol.controller.common.fsm.FsmKey;
import com.dcontrol.controller.common.fsm.FsmKeyConverter;
import java.util.UUID;

/** States of FSM {@code PAIRING}; keys match V4__device_pairing.sql. */
public enum PairingState implements FsmKey {
    ISSUED("01900000-0000-7000-8000-000000000101"),
    CLAIMED("01900000-0000-7000-8000-000000000102"),
    CANCELLED("01900000-0000-7000-8000-000000000103");

    private final UUID key;

    PairingState(String key) {
        this.key = UUID.fromString(key);
    }

    @Override
    public UUID key() {
        return key;
    }

    public static class Converter extends FsmKeyConverter<PairingState> {
        public Converter() {
            super(PairingState.class);
        }
    }
}
