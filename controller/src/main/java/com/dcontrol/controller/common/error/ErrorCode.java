package com.dcontrol.controller.common.error;

/** Machine-readable error codes shared by REST problem details and WebSocket {@code error} messages. */
public enum ErrorCode {
    VALIDATION,
    NOT_FOUND,
    CONFLICT,
    CONCURRENT_MODIFICATION,
    INVALID_STATE_TRANSITION,
    UNAUTHORIZED,
    FORBIDDEN,
    INVALID_CREDENTIALS,
    EMAIL_NOT_VERIFIED,
    EMAIL_TAKEN,
    TOKEN_INVALID,
    PAIRING_TOKEN_INVALID,
    RATE_LIMITED,
    DEVICE_OFFLINE,
    DEVICE_DISCONNECTED,
    TIMEOUT,
    UNSUPPORTED,
    PERMISSION_REQUIRED,
    PAYLOAD_TOO_LARGE,
    INTERNAL
}
