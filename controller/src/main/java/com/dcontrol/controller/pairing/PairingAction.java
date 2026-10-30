package com.dcontrol.controller.pairing;

import com.dcontrol.controller.common.fsm.FsmKey;
import com.dcontrol.controller.common.fsm.FsmKeyConverter;
import java.util.UUID;

/** Actions of FSM {@code PAIRING}; keys match V4__device_pairing.sql. */
public enum PairingAction implements FsmKey {
    ISSUE("01900000-0000-7000-8000-000000000151"),
    CLAIM("01900000-0000-7000-8000-000000000152"),
    CANCEL("01900000-0000-7000-8000-000000000153");

    private final UUID key;

    PairingAction(String key) {
        this.key = UUID.fromString(key);
    }

    @Override
    public UUID key() {
        return key;
    }

    public static class Converter extends FsmKeyConverter<PairingAction> {
        public Converter() {
            super(PairingAction.class);
        }
    }
}
