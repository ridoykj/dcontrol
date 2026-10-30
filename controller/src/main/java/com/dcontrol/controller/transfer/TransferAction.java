package com.dcontrol.controller.transfer;

import com.dcontrol.controller.common.fsm.FsmKey;
import com.dcontrol.controller.common.fsm.FsmKeyConverter;
import java.util.UUID;

/** Actions of FSM {@code TRANSFER}; keys match V5__file_transfer.sql. */
public enum TransferAction implements FsmKey {
    CREATE("01900000-0000-7000-8000-000000000351"),
    STAGE("01900000-0000-7000-8000-000000000352"),
    DELIVER("01900000-0000-7000-8000-000000000353"),
    FAIL("01900000-0000-7000-8000-000000000354"),
    CANCEL("01900000-0000-7000-8000-000000000355"),
    EXPIRE("01900000-0000-7000-8000-000000000356");

    private final UUID key;

    TransferAction(String key) {
        this.key = UUID.fromString(key);
    }

    @Override
    public UUID key() {
        return key;
    }

    public static class Converter extends FsmKeyConverter<TransferAction> {
        public Converter() {
            super(TransferAction.class);
        }
    }
}
