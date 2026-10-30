package com.dcontrol.controller.common.fsm;

/**
 * Entity with {@code id_state_key} / {@code id_action_key}. Only {@link StateMachine#move} may call
 * {@link #applyTransition}.
 */
public interface StatefulEntity<S extends Enum<S> & FsmKey, A extends Enum<A> & FsmKey> {

    S getState();

    A getLastAction();

    void applyTransition(S state, A action);
}
