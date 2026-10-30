package com.dcontrol.controller.mic;

import com.dcontrol.controller.common.ws.RelayedCommand;
import com.dcontrol.controller.webrtc.IceServers.IceServer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** {@code mic.*} payloads (ws-v1.md, Phase 6). SDP is forwarded, never logged. */
public final class MicProtocol {

    private MicProtocol() {
    }

    public static final int MAX_SDP = 32_768;
    public static final int MIN_CLIP_MS = 1_000;

    public enum State { AWAITING_CONSENT, STARTING, LIVE, ENDED }

    public enum EndReason {
        DENIED, STOPPED_ON_PHONE, STOPPED, ERROR,
        /* Set by the controller only. */
        TIMEOUT, DEVICE_OFFLINE, REPLACED
    }

    public enum SignalKind { OFFER, ANSWER }

    private static final Set<EndReason> PHONE_REASONS =
            EnumSet.of(EndReason.DENIED, EndReason.STOPPED_ON_PHONE, EndReason.STOPPED, EndReason.ERROR);

    public record StartCommand(@NotNull UUID deviceId) implements RelayedCommand.Targeted {

        /** Not used: the phone payload needs the session, see {@link StartToDevice}. */
        @Override
        public Object toDevice() {
            return null;
        }
    }

    public record StartToDevice(UUID sessionId, String viewer, List<IceServer> iceServers) {
    }

    /** The phone answers with an empty object; nothing in it is used. */
    public record StartResult() {
    }

    public record StartForWeb(UUID sessionId, List<IceServer> iceServers) {
    }

    public record StateEvent(@NotNull UUID sessionId, @NotNull State state, EndReason reason) {

        @JsonIgnore
        @AssertTrue(message = "reason is required with ENDED, not allowed otherwise, and limited to phone reasons")
        public boolean isReasonValid() {
            if (state != State.ENDED) {
                return reason == null;
            }
            return reason != null && PHONE_REASONS.contains(reason);
        }
    }

    public record StateForWeb(UUID deviceId, UUID sessionId, State state, EndReason reason) {
    }

    /** {@code mic.signal} from either side, and what the phone receives. */
    public record Signal(@NotNull UUID sessionId, @NotNull SignalKind kind,
                         @NotBlank @Size(max = MAX_SDP) String sdp) {
    }

    public record SignalForWeb(UUID deviceId, UUID sessionId, SignalKind kind, String sdp) {
    }

    public record Stop(@NotNull UUID sessionId) {
    }

    /** {@code mic.capture} from the browser. The configured maximum clip length is checked by the service. */
    public record CaptureCommand(@NotNull UUID sessionId, @NotNull @Min(MIN_CLIP_MS) Integer durationMs) {
    }

    public record CaptureToDevice(UUID sessionId, UUID mediaId, Integer durationMs) {
    }

    /** What the phone reports after its upload; the type, size and length come from the upload itself. */
    public record CaptureResult(@Min(0) Integer durationMs) {
    }

    public record CaptureForWeb(UUID mediaId, String mime, long bytes, Long durationMs, Instant capturedAt) {
    }

    /** Lets {@code RelayGuards} key a limit by the session's phone. */
    record DeviceTarget(UUID deviceId) implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return null;
        }
    }
}
