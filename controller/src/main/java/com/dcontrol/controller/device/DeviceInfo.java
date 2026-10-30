package com.dcontrol.controller.device;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Hardware and app facts reported by the phone at pairing and in {@code device.hello}. */
public record DeviceInfo(
        @NotBlank @Size(max = 64) String manufacturer,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 16) String androidVersion,
        @NotNull @Min(26) @Max(100) Integer sdkInt,
        @NotBlank @Size(max = 32) String appVersion) {
}
