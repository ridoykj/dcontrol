package com.dcontrol.controller.findphone;

import com.dcontrol.controller.common.validation.PhoneNumber;
import com.dcontrol.controller.common.ws.RelayedCommand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** {@code find.*} payloads and the stored location (ws-v1.md, Phase 4). */
public final class FindProtocol {

    private FindProtocol() {
    }

    /** 1–200 chars; line breaks allowed, other control characters not. */
    public static final String LOCK_MESSAGE = "^(?:[^\\p{Cntrl}]|\\n){1,200}$";

    public enum RingAction { START, STOP }

    public enum Provider { GPS, NETWORK, FUSED, PASSIVE, OTHER }

    public record RingCommand(@NotNull UUID deviceId, @NotNull RingAction action) implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return Map.of("action", action);
        }
    }

    public record LockCommand(
            @NotNull UUID deviceId,
            @NotNull @Pattern(regexp = LOCK_MESSAGE, message = "must be 1–200 characters; line breaks are the only control characters allowed") String message,
            @PhoneNumber String contact) implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return new LockToDevice(message, contact);
        }
    }

    public record LockToDevice(String message, String contact) {
    }

    /** A phone's {@code find.locate} result. */
    public record Location(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull @DecimalMin("0") @DecimalMax("1000000") Double accuracyM,
            @DecimalMin("-100000") @DecimalMax("100000") Double altitudeM,
            @NotNull Instant fixedAt,
            @NotNull Provider provider,
            @NotNull Boolean stale) {
    }

    /** The stored fix as the web sees it. */
    public record StoredLocation(UUID deviceId, double latitude, double longitude, double accuracyM,
                                 Double altitudeM, Instant fixedAt, Provider provider, boolean stale,
                                 Instant updatedAt) {
    }
}
