package com.dcontrol.controller.screen;

import com.dcontrol.controller.common.ws.RelayedCommand;
import com.dcontrol.controller.webrtc.IceServers.IceServer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** {@code screen.*} and {@code rtc.signal} payloads (ws-v1.md, Phase 4). SDP is forwarded, never logged. */
public final class ScreenProtocol {

    private ScreenProtocol() {
    }

    public static final int MAX_SDP = 32_768;

    public enum Quality { LOW, MEDIUM, HIGH }

    public enum State { AWAITING_CONSENT, STARTING, LIVE, ENDED }

    public enum EndReason {
        DENIED, STOPPED_ON_PHONE, STOPPED, ERROR,
        /* Set by the controller only. */
        TIMEOUT, DEVICE_OFFLINE, REPLACED
    }

    public enum SignalKind { OFFER, ANSWER }

    private static final Set<EndReason> PHONE_REASONS =
            EnumSet.of(EndReason.DENIED, EndReason.STOPPED_ON_PHONE, EndReason.STOPPED, EndReason.ERROR);

    public record StartCommand(@NotNull UUID deviceId, @NotNull Quality quality, @NotNull Boolean audio,
                               @NotNull Boolean control) implements RelayedCommand.Targeted {

        /** Not used: the phone payload needs the session, see {@link StartToDevice}. */
        @Override
        public Object toDevice() {
            return null;
        }
    }

    public record StartToDevice(UUID sessionId, String viewer, Quality quality, boolean audio, boolean control,
                                List<IceServer> iceServers) {
    }

    public record StartResult(@NotNull Boolean audio, @NotNull Boolean control) {
    }

    public record StartForWeb(UUID sessionId, List<IceServer> iceServers, boolean audio, boolean control) {
    }

    public record StateEvent(@NotNull UUID sessionId, @NotNull State state, EndReason reason,
                             @NotNull Boolean control) {

        @JsonIgnore
        @AssertTrue(message = "reason is required with ENDED, not allowed otherwise, and limited to phone reasons")
        public boolean isReasonValid() {
            if (state != State.ENDED) {
                return reason == null;
            }
            return reason != null && PHONE_REASONS.contains(reason);
        }
    }

    public record StateForWeb(UUID deviceId, UUID sessionId, State state, EndReason reason, boolean control) {
    }

    /** {@code rtc.signal} from either side, and what the phone receives. */
    public record Signal(@NotNull UUID sessionId, @NotNull SignalKind kind,
                         @NotBlank @Size(max = MAX_SDP) String sdp) {
    }

    public record SignalForWeb(UUID deviceId, UUID sessionId, SignalKind kind, String sdp) {
    }

    public record Stop(@NotNull UUID sessionId) {
    }
}
