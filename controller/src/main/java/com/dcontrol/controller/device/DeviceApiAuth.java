package com.dcontrol.controller.device;

import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/** Authenticates phone HTTP requests with the same {@code Authorization: Device id.secret} as the socket. */
@Component
public class DeviceApiAuth {

    public record DeviceCaller(UUID deviceKey, UUID accountKey) {

        public Actor actor() {
            return Actor.device(accountKey, deviceKey);
        }
    }

    private final DeviceAuthService deviceAuth;

    public DeviceApiAuth(DeviceAuthService deviceAuth) {
        this.deviceAuth = deviceAuth;
    }

    public DeviceCaller require(HttpServletRequest request) {
        return deviceAuth.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION))
                .map(device -> new DeviceCaller(device.deviceKey(), device.accountKey()))
                .orElseThrow(() -> ApiException.unauthorized(ErrorCode.UNAUTHORIZED, "Device credentials are invalid"));
    }
}
