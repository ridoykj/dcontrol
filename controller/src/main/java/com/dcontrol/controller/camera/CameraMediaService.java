package com.dcontrol.controller.camera;

import com.dcontrol.controller.camera.CameraProtocol.CaptureKind;
import com.dcontrol.controller.common.config.AppProperties;
import com.dcontrol.controller.media.CaptureMediaStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Photos and clips phones upload during a camera session (ws-v1.md, Phase 5), spooled in {@code <storage.dir>/camera}. */
@Service
public class CameraMediaService extends CaptureMediaStore {

    public static final String JPEG_TYPE = "image/jpeg";
    public static final String MP4_TYPE = "video/mp4";

    private final Format photo;
    private final Format video;

    public CameraMediaService(AppProperties properties, Clock clock) {
        super(Path.of(properties.storage().dir()).resolve("camera"), clock, properties.camera().captureTimeout(),
                properties.camera().mediaRetention(), properties.camera().maxRetained());
        AppProperties.Camera camera = properties.camera();
        this.photo = new Format(JPEG_TYPE, Container.JPEG, camera.maxPhotoSize().toBytes(), 0, "photo");
        this.video = new Format(MP4_TYPE, Container.MP4, camera.maxVideoSize().toBytes(),
                camera.maxVideoDuration().toMillis(), "clip");
    }

    /** Opens a slot for one capture of {@code deviceKey}. It expires with the capture command. */
    public Slot open(UUID deviceKey, CaptureKind kind) {
        return open(deviceKey, kind == CaptureKind.PHOTO ? photo : video);
    }
}
