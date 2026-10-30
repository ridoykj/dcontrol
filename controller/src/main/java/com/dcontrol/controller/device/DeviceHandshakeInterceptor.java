package com.dcontrol.controller.device;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Rejects the device handshake with 401 unless the device credentials are valid. */
@Component
public class DeviceHandshakeInterceptor implements HandshakeInterceptor {

    static final String DEVICE_KEY = "dc.deviceKey";
    static final String ACCOUNT_KEY = "dc.accountKey";

    private final DeviceAuthService authService;

    public DeviceHandshakeInterceptor(DeviceAuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        return authService.authenticate(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .map(device -> {
                    attributes.put(DEVICE_KEY, device.deviceKey());
                    attributes.put(ACCOUNT_KEY, device.accountKey());
                    return true;
                })
                .orElseGet(() -> {
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    return false;
                });
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                               Exception exception) {
    }
}
