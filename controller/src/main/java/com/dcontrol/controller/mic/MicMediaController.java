package com.dcontrol.controller.mic;

import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.device.DeviceApiAuth;
import com.dcontrol.controller.device.DeviceApiAuth.DeviceCaller;
import com.dcontrol.controller.device.DeviceService;
import com.dcontrol.controller.media.CaptureMediaStore.Media;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** Microphone clips: phones upload into the slot of a capture, browsers read kept clips (ws-v1.md, Phase 6). */
@RestController
public class MicMediaController {

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final MicMediaService media;
    private final DeviceService devices;
    private final DeviceApiAuth auth;

    public MicMediaController(MicMediaService media, DeviceService devices, DeviceApiAuth auth) {
        this.media = media;
        this.devices = devices;
        this.auth = auth;
    }

    @PutMapping("/api/device-api/mic/{mediaId}")
    public ResponseEntity<Void> upload(@PathVariable UUID mediaId, HttpServletRequest request) throws IOException {
        DeviceCaller device = auth.require(request);
        media.fill(device.deviceKey(), mediaId, request.getInputStream());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/devices/{deviceId}/mic/media/{mediaId}")
    public ResponseEntity<Resource> read(@PathVariable UUID deviceId, @PathVariable UUID mediaId) {
        devices.get(deviceId);
        Media clip = media.find(deviceId, mediaId).orElseThrow(() -> ApiException.notFound("Clip"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(clip.mime()))
                .contentLength(clip.bytes())
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(30)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("mic-" + FILE_TIME.format(clip.capturedAt()) + ".m4a")
                        .build()
                        .toString())
                .body(new FileSystemResource(media.path(mediaId)));
    }
}
