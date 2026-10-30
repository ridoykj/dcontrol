package com.dcontrol.controller.common.ws;

import com.dcontrol.controller.common.error.ErrorCode;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Commands sent to devices and awaiting a result, keyed by the controller-generated relay id.
 * Futures time out with {@link java.util.concurrent.TimeoutException}.
 */
@Component
public class PendingRequestRegistry {

    private record Pending(UUID deviceKey, CompletableFuture<Envelope> future) {
    }

    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();

    public CompletableFuture<Envelope> register(UUID relayId, UUID deviceKey, Duration timeout) {
        CompletableFuture<Envelope> future = new CompletableFuture<>();
        pending.put(relayId, new Pending(deviceKey, future));
        future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((result, error) -> pending.remove(relayId));
        return future;
    }

    /** Completes the matching command; results from another device or for unknown ids are ignored. */
    public boolean complete(UUID deviceKey, Envelope result) {
        if (result.requestId() == null) {
            return false;
        }
        Pending entry = pending.get(result.requestId());
        if (entry == null || !entry.deviceKey().equals(deviceKey)) {
            return false;
        }
        return entry.future().complete(result);
    }

    public void failAll(UUID deviceKey, ErrorCode code, String message) {
        pending.values().stream()
                .filter(entry -> entry.deviceKey().equals(deviceKey))
                .forEach(entry -> entry.future().completeExceptionally(new RelayException(code, message)));
    }

    public int size() {
        return pending.size();
    }
}
