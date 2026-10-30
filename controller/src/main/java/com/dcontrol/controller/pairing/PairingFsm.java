package com.dcontrol.controller.pairing;

import com.dcontrol.controller.common.fsm.StateMachine;
import java.util.UUID;

public final class PairingFsm {

    public static final StateMachine<PairingState, PairingAction> MACHINE = StateMachine
            .define("PAIRING", UUID.fromString("01900000-0000-7000-8000-000000000100"),
                    PairingState.class, PairingAction.class)
            .allow(null, PairingAction.ISSUE, PairingState.ISSUED)
            .allow(PairingState.ISSUED, PairingAction.CLAIM, PairingState.CLAIMED)
            .allow(PairingState.ISSUED, PairingAction.CANCEL, PairingState.CANCELLED)
            .build();

    private PairingFsm() {
    }
}
