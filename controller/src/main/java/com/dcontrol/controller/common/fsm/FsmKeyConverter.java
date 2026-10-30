package com.dcontrol.controller.common.fsm;

import jakarta.persistence.AttributeConverter;
import java.util.UUID;

/** Maps an FSM enum to its {@code id_state_key} / {@code id_action_key} column. Subclass once per enum. */
public abstract class FsmKeyConverter<E extends Enum<E> & FsmKey> implements AttributeConverter<E, UUID> {

    private final Class<E> type;

    protected FsmKeyConverter(Class<E> type) {
        this.type = type;
    }

    @Override
    public UUID convertToDatabaseColumn(E attribute) {
        return attribute == null ? null : attribute.key();
    }

    @Override
    public E convertToEntityAttribute(UUID dbData) {
        if (dbData == null) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.key().equals(dbData)) {
                return constant;
            }
        }
        throw new IllegalStateException("Unknown " + type.getSimpleName() + " key " + dbData);
    }
}
