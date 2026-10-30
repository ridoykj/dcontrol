package com.dcontrol.controller.device;

import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.context.EventChannel;
import com.dcontrol.controller.common.persistence.ActorTx;
import com.dcontrol.controller.common.security.SecureTokens;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Verifies {@code Authorization: Device <deviceId>.<secret>} on the device socket handshake. */
@Service
public class DeviceAuthService {

    public static final String SCHEME = "Device ";

    public record AuthenticatedDevice(UUID deviceKey, UUID accountKey) {
    }

    private final DeviceRepository devices;
    private final ActorTx actorTx;

    public DeviceAuthService(DeviceRepository devices, ActorTx actorTx) {
        this.devices = devices;
        this.actorTx = actorTx;
    }

    public Optional<AuthenticatedDevice> authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith(SCHEME)) {
            return Optional.empty();
        }
        String credentials = authorization.substring(SCHEME.length()).strip();
        int dot = credentials.indexOf('.');
        if (dot <= 0 || dot == credentials.length() - 1 || credentials.length() > 200) {
            return Optional.empty();
        }
        UUID deviceKey;
        try {
            deviceKey = UUID.fromString(credentials.substring(0, dot));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String secret = credentials.substring(dot + 1);
        return actorTx.read(Actor.system("device.auth", EventChannel.WS_DEVICE), () ->
                devices.findActiveInState(deviceKey, DeviceState.ACTIVE)
                        .filter(device -> SecureTokens.matches(device.getSecretHash(), secret))
                        .map(device -> new AuthenticatedDevice(device.getId(), device.getAccountKey())));
    }
}
