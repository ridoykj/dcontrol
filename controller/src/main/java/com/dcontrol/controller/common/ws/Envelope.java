package com.dcontrol.controller.common.ws;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Wire envelope: {@code {v, type, requestId?, payload?, error?}}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Envelope(Integer v, String type, UUID requestId, JsonNode payload, WsError error) {

    public static final int VERSION = 1;

    /** {@code details} is only set with {@code PERMISSION_REQUIRED}; see {@link Details}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WsError(String code, String message, Details details) {

        public WsError(String code, String message) {
            this(code, message, null);
        }
    }

    /** {@code permission} names the phone access a {@code PERMISSION_REQUIRED} error is about. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Details(String permission) {
    }

    @JsonIgnore
    public boolean isResult() {
        return type != null && type.endsWith(MessageType.RESULT_SUFFIX);
    }
}
