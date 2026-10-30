package com.dcontrol.controller.common.ws;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Open device sockets by device key. At most one per device; a newer one supersedes the older. */
@Component
public class DeviceSessionRegistry {

    private final ConcurrentHashMap<UUID, WsConnection> byDevice = new ConcurrentHashMap<>();

    /** Registers the connection and returns the one it replaced, if any. */
    public Optional<WsConnection> register(UUID deviceKey, WsConnection connection) {
        WsConnection previous = byDevice.put(deviceKey, connection);
        return previous != null && previous != connection ? Optional.of(previous) : Optional.empty();
    }

    /** Removes the mapping only if it still points to this connection. */
    public boolean unregister(UUID deviceKey, WsConnection connection) {
        return byDevice.remove(deviceKey, connection);
    }

    public Optional<WsConnection> get(UUID deviceKey) {
        return Optional.ofNullable(byDevice.get(deviceKey));
    }

    public boolean isOnline(UUID deviceKey) {
        WsConnection connection = byDevice.get(deviceKey);
        return connection != null && connection.isOpen();
    }

    public Map<UUID, WsConnection> snapshot() {
        return Map.copyOf(byDevice);
    }
}
