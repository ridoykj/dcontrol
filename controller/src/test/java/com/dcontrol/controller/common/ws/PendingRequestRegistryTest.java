package com.dcontrol.controller.common.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.common.error.ErrorCode;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class PendingRequestRegistryTest {

    private final PendingRequestRegistry registry = new PendingRequestRegistry();

    @Test
    void completesOnlyForTheOwningDevice() throws Exception {
        UUID device = UUID.randomUUID();
        UUID relayId = UUID.randomUUID();
        CompletableFuture<Envelope> future = registry.register(relayId, device, Duration.ofSeconds(5));
        Envelope result = new Envelope(1, "device.ping.result", relayId, null, null);

        assertThat(registry.complete(UUID.randomUUID(), result)).isFalse();
        assertThat(future).isNotDone();
        assertThat(registry.complete(device, result)).isTrue();
        assertThat(future.get(1, TimeUnit.SECONDS)).isSameAs(result);
        assertThat(registry.size()).isZero();
        assertThat(registry.complete(device, result)).isFalse();
    }

    @Test
    void timesOut() {
        CompletableFuture<Envelope> future = registry.register(UUID.randomUUID(), UUID.randomUUID(),
                Duration.ofMillis(50));
        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(TimeoutException.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat(registry.size()).isZero();
    }

    @Test
    void failAllFailsOnlyThatDevice() {
        UUID device = UUID.randomUUID();
        CompletableFuture<Envelope> mine = registry.register(UUID.randomUUID(), device, Duration.ofSeconds(5));
        CompletableFuture<Envelope> other = registry.register(UUID.randomUUID(), UUID.randomUUID(), Duration.ofSeconds(5));

        registry.failAll(device, ErrorCode.DEVICE_DISCONNECTED, "gone");

        assertThat(mine).isCompletedExceptionally();
        assertThat(mine.exceptionNow()).isInstanceOfSatisfying(RelayException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.DEVICE_DISCONNECTED));
        assertThat(other).isNotDone();
    }
}
