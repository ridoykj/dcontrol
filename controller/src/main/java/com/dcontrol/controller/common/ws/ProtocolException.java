package com.dcontrol.controller.common.ws;

import com.dcontrol.controller.common.error.ErrorCode;
import java.util.UUID;

/** A message the peer sent that violates the protocol; answered with an {@code error} message. */
public class ProtocolException extends RuntimeException {

    private final ErrorCode code;
    private final UUID requestId;

    public ProtocolException(ErrorCode code, String message, UUID requestId) {
        super(message);
        this.code = code;
        this.requestId = requestId;
    }

    public static ProtocolException validation(String message, UUID requestId) {
        return new ProtocolException(ErrorCode.VALIDATION, message, requestId);
    }

    public ErrorCode code() {
        return code;
    }

    public UUID requestId() {
        return requestId;
    }
}
