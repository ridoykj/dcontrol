package com.dcontrol.controller.common.ws;

import org.springframework.web.socket.CloseStatus;

/** Application close codes of protocol v1. */
public final class CloseCodes {

    public static final CloseStatus UNAUTHORIZED = new CloseStatus(4001, "Unauthorized");
    public static final CloseStatus REVOKED = new CloseStatus(4003, "Device revoked");
    public static final CloseStatus SUPERSEDED = new CloseStatus(4009, "Superseded by a newer connection");
    public static final CloseStatus PROTOCOL_VIOLATION = new CloseStatus(4400, "Protocol violation");
    public static final CloseStatus RATE_LIMITED = new CloseStatus(4429, "Rate limited");
    public static final CloseStatus HEARTBEAT_TIMEOUT = new CloseStatus(1000, "Heartbeat timeout");

    private CloseCodes() {
    }
}
