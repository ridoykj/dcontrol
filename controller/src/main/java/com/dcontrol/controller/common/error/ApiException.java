package com.dcontrol.controller.common.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Expected failure that becomes a problem-details response with the given status and code. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;
    private final Map<String, Object> properties;

    public ApiException(HttpStatus status, ErrorCode code, String detail) {
        this(status, code, detail, Map.of());
    }

    /** @param properties extra problem-details members, e.g. {@code receivedBytes} on a chunk conflict */
    public ApiException(HttpStatus status, ErrorCode code, String detail, Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, what + " not found");
    }

    public static ApiException unauthorized(ErrorCode code, String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, detail);
    }

    public static ApiException rateLimited() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED, "Too many requests, try again later");
    }

    public static ApiException validation(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, detail);
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
