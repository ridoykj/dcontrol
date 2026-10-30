package com.dcontrol.controller.camera;

import com.dcontrol.controller.common.ws.RelayedCommand;
import com.dcontrol.controller.webrtc.IceServers.IceServer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** {@code camera.*} payloads (ws-v1.md, Phase 5). SDP is forwarded, never logged. */
public final class CameraProtocol {

    private CameraProtocol() {
    }

    public static final int MAX_SDP = 32_768;
    public static final int MIN_VIDEO_MS = 1_000;
    /* The phone's figures are only shown, so they just need to be plausible. */
    public static final int MAX_DIMENSION = 20_000;

    public enum Facing { FRONT, BACK }

    public enum State { AWAITING_CONSENT, STARTING, LIVE, ENDED }

    public enum EndReason {
        DENIED, STOPPED_ON_PHONE, STOPPED, ERROR,
        /* Set by the controller only. */
        TIMEOUT, DEVICE_OFFLINE, REPLACED
    }

    public enum SignalKind { OFFER, ANSWER }

    public enum CaptureKind { PHOTO, VIDEO }

    private static final Set<EndReason> PHONE_REASONS =
            EnumSet.of(EndReason.DENIED, EndReason.STOPPED_ON_PHONE, EndReason.STOPPED, EndReason.ERROR);

    public record StartCommand(@NotNull UUID deviceId, @NotNull Facing facing, @NotNull Boolean audio)
            implements RelayedCommand.Targeted {

        /** Not used: the phone payload needs the session, see {@link StartToDevice}. */
        @Override
        public Object toDevice() {
            return null;
        }
    }

    public record StartToDevice(UUID sessionId, String viewer, Facing facing, boolean audio,
                                List<IceServer> iceServers) {
    }

    public record StartResult(@NotNull Boolean audio) {
    }

    public record StartForWeb(UUID sessionId, List<IceServer> iceServers, boolean audio) {
    }

    public record StateEvent(@NotNull UUID sessionId, @NotNull State state, EndReason reason,
                             @NotNull Facing facing, @NotNull Boolean video) {

        @JsonIgnore
        @AssertTrue(message = "reason is required with ENDED, not allowed otherwise, and limited to phone reasons")
        public boolean isReasonValid() {
            if (state != State.ENDED) {
                return reason == null;
            }
            return reason != null && PHONE_REASONS.contains(reason);
        }
    }

    /** {@code facing} is null when the controller ends the session itself. */
    public record StateForWeb(UUID deviceId, UUID sessionId, State state, EndReason reason, Facing facing,
                              boolean video) {
    }

    /** {@code camera.signal} from either side, and what the phone receives. */
    public record Signal(@NotNull UUID sessionId, @NotNull SignalKind kind,
                         @NotBlank @Size(max = MAX_SDP) String sdp) {
    }

    public record SignalForWeb(UUID deviceId, UUID sessionId, SignalKind kind, String sdp) {
    }

    public record Stop(@NotNull UUID sessionId) {
    }

    /** {@code camera.switch} from the browser; the phone gets the same payload. */
    public record SwitchCommand(@NotNull UUID sessionId, @NotNull Facing facing) {
    }

    /** The {@code camera.switch} result on both hops. */
    public record SwitchResult(@NotNull Facing facing) {
    }

    /** {@code camera.capture} from the browser. The configured maximum clip length is checked by the service. */
    public record CaptureCommand(@NotNull UUID sessionId, @NotNull CaptureKind kind, Integer durationMs) {

        @JsonIgnore
        @AssertTrue(message = "durationMs is required for VIDEO (at least 1000) and not allowed for PHOTO")
        public boolean isDurationValid() {
            if (kind != CaptureKind.VIDEO) {
                return durationMs == null;
            }
            return durationMs != null && durationMs >= MIN_VIDEO_MS;
        }
    }

    public record CaptureToDevice(UUID sessionId, UUID mediaId, CaptureKind kind, Integer durationMs) {
    }

    /** What the phone reports after its upload; the type, size and clip length come from the upload itself. */
    public record CaptureResult(@NotNull @Min(1) @Max(MAX_DIMENSION) Integer width,
                                @NotNull @Min(1) @Max(MAX_DIMENSION) Integer height,
                                @Min(0) Integer durationMs) {
    }

    public record CaptureForWeb(UUID mediaId, CaptureKind kind, String mime, long bytes, int width, int height,
                                Long durationMs, Instant capturedAt) {
    }

    /** Lets {@code RelayGuards} key a limit by the session's phone. */
    record DeviceTarget(UUID deviceId) implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return null;
        }
    }
}
