package com.dcontrol.controller.common.ws;

import java.util.Optional;

/** Phone accesses a {@code PERMISSION_REQUIRED} error may name in {@code details.permission}. */
public enum PhonePermission {
    CONTACTS,
    CALL_LOG,
    SMS,
    PHONE,
    LOCATION,
    ACCESSIBILITY,
    CAMERA,
    MICROPHONE;

    /** Parses a value a phone sent; unknown values are dropped. */
    public static Optional<PhonePermission> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (PhonePermission permission : values()) {
            if (permission.name().equals(value)) {
                return Optional.of(permission);
            }
        }
        return Optional.empty();
    }
}
