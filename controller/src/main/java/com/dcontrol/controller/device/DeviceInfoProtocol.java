package com.dcontrol.controller.device;

import com.dcontrol.controller.common.ws.RelayedCommand;
import com.dcontrol.controller.files.FilesProtocol.Volume;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code device.info} payloads (ws-v1.md, Phase 2). Read on demand; nothing is stored. */
public final class DeviceInfoProtocol {

    private DeviceInfoProtocol() {
    }

    public enum BatteryHealth { GOOD, OVERHEAT, DEAD, OVER_VOLTAGE, COLD, FAILURE, UNKNOWN }

    public enum Plugged { AC, USB, WIRELESS, DOCK, NONE }

    public enum NetworkType { WIFI, CELLULAR, ETHERNET, VPN, OTHER, NONE }

    public record InfoCommand(@NotNull UUID deviceId) implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return Map.of();
        }
    }

    public record Os(
            @NotBlank @Size(max = 16) String androidVersion,
            @NotNull @Min(26) @Max(100) Integer sdkInt,
            @Size(max = 16) String securityPatch,
            @NotBlank @Size(max = 64) String buildId,
            @NotBlank @Size(max = 64) String manufacturer,
            @NotBlank @Size(max = 64) String model,
            @NotNull @Size(max = 8) List<@NotBlank @Size(max = 32) String> abis) {
    }

    public record Memory(
            @NotNull @PositiveOrZero Long totalBytes,
            @NotNull @PositiveOrZero Long availableBytes,
            @NotNull Boolean lowMemory) {
    }

    public record BatteryDetails(
            @NotNull @Min(0) @Max(100) Integer level,
            @NotNull Boolean charging,
            @NotNull BatteryHealth health,
            @NotNull Plugged plugged,
            @NotNull @DecimalMin("-50") @DecimalMax("150") Double temperatureC,
            @NotNull @Min(0) @Max(20000) Integer voltageMv) {
    }

    public record Display(
            @NotNull @Min(1) @Max(16384) Integer widthPx,
            @NotNull @Min(1) @Max(16384) Integer heightPx,
            @NotNull @Min(1) @Max(2000) Integer densityDpi,
            @NotNull @DecimalMin("0") @DecimalMax("1000") Double refreshRateHz) {
    }

    public record Network(
            @NotNull NetworkType type,
            @NotNull Boolean metered,
            @NotNull @Size(max = 16) List<@NotBlank @Size(max = 64) String> ipAddresses) {
    }

    public record InfoResult(
            @NotNull @Valid Os os,
            @NotNull @PositiveOrZero Long uptimeMs,
            @NotNull @Min(1) @Max(1024) Integer cpuCores,
            @NotNull @Valid Memory memory,
            @NotNull @Size(max = 16) List<@NotNull @Valid Volume> storage,
            @NotNull @Valid BatteryDetails battery,
            @NotNull @Valid Display display,
            @NotNull @Valid Network network,
            @NotBlank @Size(max = 35) String locale,
            @NotBlank @Size(max = 64) String timeZone) {
    }
}
