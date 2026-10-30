package com.dcontrol.controller.common.fsm;

import java.util.UUID;

/** A state or action whose key is seeded in T_FSM_STATE / T_FSM_ACTION. */
public interface FsmKey {

    UUID key();

    /** Code stored in tx_code, equal to the enum constant name. */
    String name();
}
