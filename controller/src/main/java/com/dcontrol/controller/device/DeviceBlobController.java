package com.dcontrol.controller.device;

import com.dcontrol.controller.common.blob.PhoneBlobRelay;
import com.dcontrol.controller.device.DeviceApiAuth.DeviceCaller;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** Phones fill upload slots the controller asked for (ws-v1.md, "Contact photos"). */
@RestController
public class DeviceBlobController {

    private final PhoneBlobRelay blobs;
    private final DeviceApiAuth auth;

    public DeviceBlobController(PhoneBlobRelay blobs, DeviceApiAuth auth) {
        this.blobs = blobs;
        this.auth = auth;
    }

    @PutMapping("/api/device-api/blobs/{uploadId}")
    public ResponseEntity<Void> upload(@PathVariable UUID uploadId, HttpServletRequest request) throws IOException {
        DeviceCaller device = auth.require(request);
        blobs.fill(device.deviceKey(), uploadId, request.getInputStream());
        return ResponseEntity.noContent().build();
    }
}
