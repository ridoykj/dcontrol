package com.dcontrol.controller.common.security;

import com.dcontrol.controller.common.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** In-memory fixed-window limiter for login, email and pairing endpoints. Single node only. */
@Component
public class RateLimiter {

    private record Window(long startMillis, long lengthMillis, AtomicInteger count) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        Window current = windows.compute(key, (k, existing) ->
                existing == null || now - existing.startMillis() >= existing.lengthMillis()
                        ? new Window(now, window.toMillis(), new AtomicInteger())
                        : existing);
        return current.count().incrementAndGet() <= limit;
    }

    public void check(String key, int limit, Duration window) {
        if (!tryAcquire(key, limit, window)) {
            throw ApiException.rateLimited();
        }
    }

    public void reset() {
        windows.clear();
    }

    @Scheduled(fixedDelay = 60_000)
    void evictExpired() {
        long now = clock.millis();
        windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= e.getValue().lengthMillis());
    }
}
