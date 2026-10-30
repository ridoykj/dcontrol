package com.dcontrol.controller.common.fsm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.common.error.ErrorCode;
import com.dcontrol.controller.pairing.PairingAction;
import com.dcontrol.controller.pairing.PairingFsm;
import com.dcontrol.controller.pairing.PairingState;
import org.junit.jupiter.api.Test;

class StateMachineTest {

    private static final class Entity implements StatefulEntity<PairingState, PairingAction> {
        PairingState state;
        PairingAction action;

        @Override
        public PairingState getState() {
            return state;
        }

        @Override
        public PairingAction getLastAction() {
            return action;
        }

        @Override
        public void applyTransition(PairingState state, PairingAction action) {
            this.state = state;
            this.action = action;
        }
    }

    @Test
    void movesAlongRegisteredTransitions() {
        Entity entity = new Entity();
        PairingFsm.MACHINE.move(entity, PairingAction.ISSUE);
        assertThat(entity.state).isEqualTo(PairingState.ISSUED);
        PairingFsm.MACHINE.move(entity, PairingAction.CLAIM);
        assertThat(entity.state).isEqualTo(PairingState.CLAIMED);
        assertThat(entity.action).isEqualTo(PairingAction.CLAIM);
    }

    @Test
    void rejectsUnregisteredTransitionWithConflict() {
        Entity entity = new Entity();
        PairingFsm.MACHINE.move(entity, PairingAction.ISSUE);
        PairingFsm.MACHINE.move(entity, PairingAction.CANCEL);

        assertThatThrownBy(() -> PairingFsm.MACHINE.move(entity, PairingAction.CLAIM))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
                    assertThat(e.status().value()).isEqualTo(409);
                });
        assertThat(entity.state).isEqualTo(PairingState.CANCELLED);
    }

    @Test
    void newEntityOnlyAcceptsTheCreatingAction() {
        Entity entity = new Entity();
        assertThat(PairingFsm.MACHINE.can(entity, PairingAction.ISSUE)).isTrue();
        assertThat(PairingFsm.MACHINE.can(entity, PairingAction.CLAIM)).isFalse();
    }

    @Test
    void duplicateTransitionsAreRejectedAtDefinition() {
        assertThatThrownBy(() -> StateMachine
                .define("X", java.util.UUID.randomUUID(), PairingState.class, PairingAction.class)
                .allow(null, PairingAction.ISSUE, PairingState.ISSUED)
                .allow(null, PairingAction.ISSUE, PairingState.CLAIMED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
