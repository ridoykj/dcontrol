package com.dcontrol.controller.common.fsm;

import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.common.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Java side of an FSM. The same transitions are registered in the database with {@code LIB_fsm_allow};
 * the TR_<t>_fsm trigger is the backstop, and {@code FsmCatalogIntegrationTest} keeps both in sync.
 */
public final class StateMachine<S extends Enum<S> & FsmKey, A extends Enum<A> & FsmKey> {

    public record Transition<S, A>(S from, A action, S to) {
    }

    private record Edge<S, A>(S from, A action) {
    }

    private final String code;
    private final UUID key;
    private final Class<S> stateType;
    private final Class<A> actionType;
    private final Map<Edge<S, A>, S> edges;

    private StateMachine(String code, UUID key, Class<S> stateType, Class<A> actionType, Map<Edge<S, A>, S> edges) {
        this.code = code;
        this.key = key;
        this.stateType = stateType;
        this.actionType = actionType;
        this.edges = Map.copyOf(edges);
    }

    public static <S extends Enum<S> & FsmKey, A extends Enum<A> & FsmKey> Builder<S, A> define(
            String code, UUID key, Class<S> stateType, Class<A> actionType) {
        return new Builder<>(code, key, stateType, actionType);
    }

    /** Applies {@code action} to the entity's current state (null for a new entity) or throws 409. */
    public void move(StatefulEntity<S, A> entity, A action) {
        S from = entity.getState();
        S to = edges.get(new Edge<>(from, action));
        if (to == null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot %s when %s".formatted(action.name().toLowerCase(),
                            from == null ? "new" : from.name().toLowerCase()));
        }
        entity.applyTransition(to, action);
    }

    public boolean can(StatefulEntity<S, A> entity, A action) {
        return edges.containsKey(new Edge<>(entity.getState(), action));
    }

    public String code() {
        return code;
    }

    public UUID key() {
        return key;
    }

    public Class<S> stateType() {
        return stateType;
    }

    public Class<A> actionType() {
        return actionType;
    }

    public List<Transition<S, A>> transitions() {
        return edges.entrySet().stream()
                .map(e -> new Transition<>(e.getKey().from(), e.getKey().action(), e.getValue()))
                .toList();
    }

    public static final class Builder<S extends Enum<S> & FsmKey, A extends Enum<A> & FsmKey> {

        private final String code;
        private final UUID key;
        private final Class<S> stateType;
        private final Class<A> actionType;
        private final Map<Edge<S, A>, S> edges = new LinkedHashMap<>();

        private Builder(String code, UUID key, Class<S> stateType, Class<A> actionType) {
            this.code = code;
            this.key = key;
            this.stateType = stateType;
            this.actionType = actionType;
        }

        /** {@code from == null} declares the creating transition. */
        public Builder<S, A> allow(S from, A action, S to) {
            if (edges.putIfAbsent(new Edge<>(from, action), to) != null) {
                throw new IllegalArgumentException("Duplicate transition " + from + " --" + action + "-->");
            }
            return this;
        }

        public StateMachine<S, A> build() {
            return new StateMachine<>(code, key, stateType, actionType, edges);
        }
    }
}
