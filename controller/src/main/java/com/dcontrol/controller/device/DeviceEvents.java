package com.dcontrol.controller.device;

import java.util.UUID;

/** Domain events published inside transactions and relayed to sockets after commit. */
public final class DeviceEvents {

    private DeviceEvents() {
    }

    public record PairingCompleted(UUID accountKey, UUID pairingId, UUID deviceKey) {
    }

    public record DeviceChanged(UUID accountKey, UUID deviceKey) {
    }

    public record DeviceRevoked(UUID accountKey, UUID deviceKey) {
    }
}
