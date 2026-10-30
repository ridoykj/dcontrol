package com.dcontrol.controller.transfer;

import com.dcontrol.controller.common.fsm.StateMachine;
import java.util.UUID;

public final class TransferFsm {

    public static final StateMachine<TransferState, TransferAction> MACHINE = StateMachine
            .define("TRANSFER", UUID.fromString("01900000-0000-7000-8000-000000000300"),
                    TransferState.class, TransferAction.class)
            .allow(null, TransferAction.CREATE, TransferState.RECEIVING)
            .allow(TransferState.RECEIVING, TransferAction.STAGE, TransferState.READY)
            .allow(TransferState.READY, TransferAction.DELIVER, TransferState.DELIVERED)
            .allow(TransferState.RECEIVING, TransferAction.FAIL, TransferState.FAILED)
            .allow(TransferState.READY, TransferAction.FAIL, TransferState.FAILED)
            .allow(TransferState.RECEIVING, TransferAction.CANCEL, TransferState.CANCELLED)
            .allow(TransferState.READY, TransferAction.CANCEL, TransferState.CANCELLED)
            .allow(TransferState.RECEIVING, TransferAction.EXPIRE, TransferState.EXPIRED)
            .allow(TransferState.READY, TransferAction.EXPIRE, TransferState.EXPIRED)
            .build();

    private TransferFsm() {
    }
}
