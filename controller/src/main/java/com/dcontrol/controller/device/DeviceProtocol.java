package com.dcontrol.controller.device;

import com.dcontrol.controller.common.ws.RelayedCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Payload records of protocol v1 (docs/protocol/ws-v1.md). Inbound ones carry validation rules. */
public final class DeviceProtocol {

    private DeviceProtocol() {
    }

    public record Battery(@NotNull @Min(0) @Max(100) Integer level, @NotNull Boolean charging) {
    }

    public record Hello(
            @NotBlank @Size(max = 32) String appVersion,
            @NotBlank @Size(max = 64) String manufacturer,
            @NotBlank @Size(max = 64) String model,
            @NotBlank @Size(max = 16) String androidVersion,
            @NotNull @Min(26) @Max(100) Integer sdkInt,
            @NotNull @Valid Battery battery) {

        public DeviceInfo info() {
            return new DeviceInfo(manufacturer, model, androidVersion, sdkInt, appVersion);
        }
    }

    public record Heartbeat(@NotNull @Valid Battery battery) {
    }

    public record HelloAck(Instant serverTime, long heartbeatIntervalSec, String deviceName) {
    }

    public record PingResult(@NotNull Instant deviceTime) {
    }

    public record PingCommand(@NotNull UUID deviceId) implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return null;
        }
    }

    public record PingResultForWeb(long rttMs, Instant deviceTime) {
    }

    public record DeviceStatusView(UUID deviceId, String name, boolean online, Battery battery, Instant lastSeen) {
    }

    public record DevicesSnapshot(List<DeviceStatusView> devices) {
    }

    public record PairingCompleted(UUID pairingId, UUID deviceId) {
    }
}
