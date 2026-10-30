package com.dcontrol.controller.notification;

import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.device.DeviceApiAuth;
import com.dcontrol.controller.device.DeviceApiAuth.DeviceCaller;
import com.dcontrol.controller.device.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** App icons for mirrored notifications: phones upload them, browsers of the owning account read them. */
@RestController
public class AppIconController {

    private final AppIconStore icons;
    private final DeviceService devices;
    private final DeviceApiAuth auth;

    public AppIconController(AppIconStore icons, DeviceService devices, DeviceApiAuth auth) {
        this.icons = icons;
        this.devices = devices;
        this.auth = auth;
    }

    @GetMapping("/api/devices/{deviceId}/app-icons/{hash}")
    public ResponseEntity<byte[]> get(@PathVariable UUID deviceId, @PathVariable String hash) {
        devices.get(deviceId);
        byte[] png = icons.read(deviceId, hash).orElseThrow(() -> ApiException.notFound("Icon"));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(png);
    }

    @RequestMapping(path = "/api/device-api/app-icons/{hash}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> exists(@PathVariable String hash, HttpServletRequest request) {
        DeviceCaller device = auth.require(request);
        return icons.exists(device.deviceKey(), hash)
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }

    @PutMapping("/api/device-api/app-icons/{hash}")
    public ResponseEntity<Void> upload(@PathVariable String hash, HttpServletRequest request) throws IOException {
        DeviceCaller device = auth.require(request);
        icons.store(device.deviceKey(), hash, request.getInputStream());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
