package com.dcontrol.controller.mic;

import com.dcontrol.controller.common.config.AppProperties;
import com.dcontrol.controller.media.CaptureMediaStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Audio clips phones upload during a microphone session (ws-v1.md, Phase 6), spooled in {@code <storage.dir>/mic}. */
@Service
public class MicMediaService extends CaptureMediaStore {

    /** AAC in an MPEG-4 container; the spool checks the container and the length, not the codec. */
    public static final String AUDIO_TYPE = "audio/mp4";

    private final Format audio;

    public MicMediaService(AppProperties properties, Clock clock) {
        super(Path.of(properties.storage().dir()).resolve("mic"), clock, properties.mic().captureTimeout(),
                properties.mic().mediaRetention(), properties.mic().maxRetained());
        AppProperties.Mic mic = properties.mic();
        this.audio = new Format(AUDIO_TYPE, Container.MP4, mic.maxAudioSize().toBytes(),
                mic.maxAudioDuration().toMillis(), "audio clip");
    }

    /** Opens a slot for one clip of {@code deviceKey}. It expires with the capture command. */
    public Slot open(UUID deviceKey) {
        return open(deviceKey, audio);
    }
}
