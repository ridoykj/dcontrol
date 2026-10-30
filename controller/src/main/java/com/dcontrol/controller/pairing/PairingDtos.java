package com.dcontrol.controller.pairing;

import com.dcontrol.controller.device.DeviceInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class PairingDtos {

    private PairingDtos() {
    }

    /** {@code qrPayload} is only returned when the token is created; the raw token is never stored. */
    public record PairingResponse(UUID id, PairingState state, Instant expiresAt, UUID deviceId, String qrPayload) {
    }

    public record TokenRequest(@NotBlank @Size(max = 100) String token) {
    }

    public record PreviewResponse(String accountEmail, Instant expiresAt) {
    }

    public record ClaimDevice(
            @NotBlank @Size(max = 64) String name,
            @NotBlank @Size(max = 64) String manufacturer,
            @NotBlank @Size(max = 64) String model,
            @NotBlank @Size(max = 16) String androidVersion,
            @NotNull @Min(26) @Max(100) Integer sdkInt,
            @NotBlank @Size(max = 32) String appVersion) {

        public DeviceInfo info() {
            return new DeviceInfo(manufacturer, model, androidVersion, sdkInt, appVersion);
        }
    }

    public record ClaimRequest(@NotBlank @Size(max = 100) String token, @NotNull @Valid ClaimDevice device) {
    }

    public record ClaimResponse(UUID deviceId, String deviceSecret, String deviceName) {
    }
}
