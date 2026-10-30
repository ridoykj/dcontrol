package com.dcontrol.controller.common.ws;

import com.dcontrol.controller.common.error.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parses and validates inbound envelopes and payloads; builds outbound ones. */
@Component
public class MessageCodec {

    private final JsonMapper jsonMapper;
    private final Validator validator;

    public MessageCodec(JsonMapper jsonMapper, Validator validator) {
        this.jsonMapper = jsonMapper;
        this.validator = validator;
    }

    public Envelope decode(String text) {
        Envelope envelope;
        try {
            envelope = jsonMapper.readValue(text, Envelope.class);
        } catch (JacksonException e) {
            throw ProtocolException.validation("Malformed message", null);
        }
        if (envelope == null || envelope.v() == null || envelope.v() != Envelope.VERSION) {
            throw ProtocolException.validation("Unsupported protocol version",
                    envelope == null ? null : envelope.requestId());
        }
        if (envelope.type() == null || envelope.type().isBlank() || envelope.type().length() > 64) {
            throw ProtocolException.validation("Missing message type", envelope.requestId());
        }
        return envelope;
    }

    /** Converts and validates the payload; a missing payload is validated as an empty object. */
    public <T> T payload(Envelope envelope, Class<T> type) {
        JsonNode node = envelope.payload() != null ? envelope.payload() : jsonMapper.createObjectNode();
        T value;
        try {
            value = jsonMapper.treeToValue(node, type);
        } catch (JacksonException e) {
            throw ProtocolException.validation("Invalid payload for " + envelope.type(), envelope.requestId());
        }
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            ConstraintViolation<T> first = violations.stream()
                    .min(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .orElseThrow();
            throw ProtocolException.validation(
                    "payload." + first.getPropertyPath() + " " + first.getMessage(), envelope.requestId());
        }
        return value;
    }

    public String encode(Envelope envelope) {
        return jsonMapper.writeValueAsString(envelope);
    }

    public String message(MessageType type, UUID requestId, Object payload) {
        return encode(new Envelope(Envelope.VERSION, type.wire(), requestId,
                payload == null ? null : jsonMapper.valueToTree(payload), null));
    }

    public String result(String type, UUID requestId, Object payload) {
        return encode(new Envelope(Envelope.VERSION, type, requestId,
                payload == null ? null : jsonMapper.valueToTree(payload), null));
    }

    public String errorResult(String type, UUID requestId, ErrorCode code, String message) {
        return encode(new Envelope(Envelope.VERSION, type, requestId, null, new Envelope.WsError(code.name(), message)));
    }

    /** A failed relay as {@code <type>.result}, keeping {@code details.permission} when it names one. */
    public String errorResult(String type, UUID requestId, RelayException error) {
        Envelope.Details details = error.permission() == null ? null : new Envelope.Details(error.permission().name());
        return encode(new Envelope(Envelope.VERSION, type, requestId, null,
                new Envelope.WsError(error.code().name(), error.getMessage(), details)));
    }

    public String error(UUID requestId, ErrorCode code, String message) {
        return errorResult(MessageType.ERROR.wire(), requestId, code, message);
    }
}
