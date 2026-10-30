package com.dcontrol.controller.device;

import com.dcontrol.controller.device.DeviceProtocol.DeviceStatusView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class DeviceDtos {

    private DeviceDtos() {
    }

    public record DeviceResponse(
            UUID id,
            String name,
            String manufacturer,
            String model,
            String androidVersion,
            int sdkInt,
            String appVersion,
            DeviceState state,
            boolean active,
            Instant pairedAt,
            Instant modifiedAt,
            DeviceStatusView status) {

        static DeviceResponse of(Device device, DeviceStatusView status) {
            return new DeviceResponse(device.getId(), device.getName(), device.getManufacturer(), device.getModel(),
                    device.getAndroidVersion(), device.getSdkInt(), device.getAppVersion(), device.getState(),
                    device.isActive(), device.getPairedAt(), device.getModifiedAt(), status);
        }
    }

    public record RenameRequest(@NotBlank @Size(max = 64) String name) {
    }
}
