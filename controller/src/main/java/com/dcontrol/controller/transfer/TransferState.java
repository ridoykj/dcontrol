package com.dcontrol.controller.transfer;

import com.dcontrol.controller.common.fsm.FsmKey;
import com.dcontrol.controller.common.fsm.FsmKeyConverter;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** States of FSM {@code TRANSFER}; keys match V5__file_transfer.sql. */
public enum TransferState implements FsmKey {
    RECEIVING("01900000-0000-7000-8000-000000000301"),
    READY("01900000-0000-7000-8000-000000000302"),
    DELIVERED("01900000-0000-7000-8000-000000000303"),
    FAILED("01900000-0000-7000-8000-000000000304"),
    CANCELLED("01900000-0000-7000-8000-000000000305"),
    EXPIRED("01900000-0000-7000-8000-000000000306");

    /** States a transfer can still move out of; their spool files count against the account quota. */
    public static final Set<TransferState> OPEN = EnumSet.of(RECEIVING, READY);

    public static final Set<TransferState> FINAL = EnumSet.complementOf(EnumSet.copyOf(OPEN));

    private final UUID key;

    TransferState(String key) {
        this.key = UUID.fromString(key);
    }

    @Override
    public UUID key() {
        return key;
    }

    public boolean isOpen() {
        return OPEN.contains(this);
    }

    public static class Converter extends FsmKeyConverter<TransferState> {
        public Converter() {
            super(TransferState.class);
        }
    }
}
