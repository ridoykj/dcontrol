package com.dcontrol.controller.common.ws;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Open browser sockets grouped by account, for pushing status events, and by id for session-routed messages. */
@Component
public class WebSessionRegistry {

    private final ConcurrentHashMap<UUID, Set<WsConnection>> byAccount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WsConnection> byId = new ConcurrentHashMap<>();

    public void register(WsConnection connection) {
        byId.put(connection.id(), connection);
        byAccount.computeIfAbsent(connection.accountKey(), key -> ConcurrentHashMap.newKeySet()).add(connection);
    }

    public void unregister(WsConnection connection) {
        byId.remove(connection.id(), connection);
        byAccount.computeIfPresent(connection.accountKey(), (key, connections) -> {
            connections.remove(connection);
            return connections.isEmpty() ? null : connections;
        });
    }

    public void broadcast(UUID accountKey, String text) {
        Set<WsConnection> connections = byAccount.get(accountKey);
        if (connections != null) {
            connections.forEach(connection -> connection.send(text));
        }
    }

    /** Sends to one socket of the account; returns false when it is gone or belongs to another account. */
    public boolean send(UUID accountKey, String connectionId, String text) {
        WsConnection connection = byId.get(connectionId);
        if (connection == null || !connection.accountKey().equals(accountKey)) {
            return false;
        }
        connection.send(text);
        return true;
    }

    public int count(UUID accountKey) {
        Set<WsConnection> connections = byAccount.get(accountKey);
        return connections == null ? 0 : connections.size();
    }
}
