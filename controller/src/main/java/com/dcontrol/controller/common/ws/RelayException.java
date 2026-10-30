package com.dcontrol.controller.common.ws;

import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.common.error.ErrorCode;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A command to a device that failed: offline, disconnected, timed out, rejected before relaying, or answered
 * with an error. {@code permission} is set only for a phone's {@code PERMISSION_REQUIRED}.
 */
public class RelayException extends RuntimeException {

    private final ErrorCode code;
    private final PhonePermission permission;

    public RelayException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public RelayException(ErrorCode code, String message, PhonePermission permission) {
        super(message);
        this.code = code;
        this.permission = permission;
    }

    public ErrorCode code() {
        return code;
    }

    /** The missing phone access, or null. */
    public PhonePermission permission() {
        return permission;
    }

    /** The REST problem for this failure; a missing phone access becomes the {@code permission} property. */
    public ApiException toApiException() {
        HttpStatus status = switch (code) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case PAYLOAD_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case PERMISSION_REQUIRED, DEVICE_OFFLINE, DEVICE_DISCONNECTED, CONFLICT, UNSUPPORTED -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        Map<String, Object> properties = permission == null ? Map.of() : Map.of("permission", permission.name());
        return new ApiException(status, code, getMessage(), properties);
    }
}
