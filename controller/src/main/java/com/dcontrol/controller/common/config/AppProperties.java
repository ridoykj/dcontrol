package com.dcontrol.controller.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("dcontrol")
public record AppProperties(
        /* Base URL the phone uses to reach the controller; goes into the pairing QR code. */
        @NotBlank String publicBaseUrl,
        /* Base URL of the web UI; used for links in emails. */
        @NotBlank String webBaseUrl,
        @NotNull @Valid Jwt jwt,
        @NotNull @Valid Mail mail,
        @NotNull @Valid Pairing pairing,
        @NotNull @Valid Ws ws,
        @NotNull @Valid Cookie cookie,
        @NotNull @Valid Storage storage,
        @NotNull @Valid Transfers transfers,
        @NotNull @Valid Sms sms,
        @NotNull @Valid Calls calls,
        @NotNull @Valid Blobs blobs,
        @NotNull @Valid Webrtc webrtc,
        @NotNull @Valid Find find,
        @NotNull @Valid Camera camera) {

    public record Jwt(
            @NotBlank @Size(min = 32, message = "must be at least 32 bytes") String secret,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration refreshTokenTtl,
            @NotNull Duration emailVerificationTtl,
            @NotNull Duration passwordResetTtl) {
    }

    public record Mail(@NotBlank String from) {
    }

    public record Pairing(@NotNull Duration tokenTtl) {
    }

    public record Ws(
            @NotNull Duration commandTimeout,
            @NotNull Duration heartbeatInterval,
            @NotNull Duration offlineAfter,
            @NotNull Duration helloTimeout,
            @NotNull Duration statusPersistInterval,
            @NotNull Duration ticketTtl,
            @Min(1024) int maxMessageBytes,
            @NotNull List<String> allowedOrigins) {
    }

    public record Cookie(boolean secure) {
    }

    /** Local disk for the transfer spool and the app-icon cache (single node only). */
    public record Storage(@NotBlank String dir) {
    }

    /** Limits and timeouts of file transfers (docs/protocol/transfers-v1.md). */
    public record Transfers(
            @NotNull DataSize maxFileSize,
            @NotNull DataSize maxChunkSize,
            @NotNull DataSize accountQuota,
            @Min(1) int maxActivePerDevice,
            /* An open transfer without progress for this long expires. */
            @NotNull Duration stallTimeout,
            /* A READY transfer expires after this long. */
            @NotNull Duration readyTtl,
            /* Spool files are deleted this long after a transfer reached a final state. */
            @NotNull Duration spoolRetention,
            /* Final rows are physically deleted after this long. */
            @NotNull Duration rowRetention,
            @NotNull Duration ticketTtl) {
    }

    /** {@code sms.send} relays per device and minute; each costs the phone owner money. */
    public record Sms(@Min(1) int sendPerMinute) {
    }

    /** {@code call.place} relays per device and minute. */
    public record Calls(@Min(1) int placePerMinute) {
    }

    /** In-memory upload slots phones fill on request, e.g. contact photos (never written to disk). */
    public record Blobs(@NotNull DataSize maxSize, @NotNull Duration timeout) {
    }

    /**
     * Screen sessions (docs/protocol/ws-v1.md, Phase 4). ICE server URLs may contain {@code {host}}, replaced
     * with the host each socket connected to; servers with {@code turn: true} get coturn REST credentials
     * signed with {@code turnSecret}.
     */
    public record Webrtc(
            @NotNull @Size(min = 1, max = 8) List<@NotNull @Valid IceServer> iceServers,
            String turnSecret,
            /* Coturn refuses allocation refreshes once credentials expire, so this bounds a session's length. */
            @NotNull Duration credentialTtl,
            @NotNull Duration consentTimeout,
            @Min(1) int startPerMinute) {

        @AssertTrue(message = "turn-secret is required when an ICE server has turn: true")
        public boolean isTurnSecretPresent() {
            return iceServers == null || iceServers.stream().noneMatch(server -> server != null && server.turn())
                    || (turnSecret != null && !turnSecret.isBlank());
        }
    }

    public record IceServer(@NotNull @Size(min = 1, max = 4) List<@NotBlank String> urls, boolean turn) {
    }

    /** {@code find.ring} and {@code find.lock} relays per device and minute. */
    public record Find(@Min(1) int ringPerMinute, @Min(1) int lockPerMinute) {
    }

    /**
     * Camera sessions and captures (docs/protocol/ws-v1.md, Phase 5). Captures are spooled under
     * {@code <storage.dir>/camera} for {@code mediaRetention}, at most {@code maxRetained} per phone.
     */
    public record Camera(
            @NotNull Duration consentTimeout,
            @Min(1) int startPerMinute,
            @Min(1) int capturePerMinute,
            /* A clip of maxVideoDuration plus its upload has to fit in here. */
            @NotNull Duration captureTimeout,
            @NotNull DataSize maxPhotoSize,
            @NotNull DataSize maxVideoSize,
            @NotNull Duration maxVideoDuration,
            @NotNull Duration mediaRetention,
            @Min(1) int maxRetained) {

        @AssertTrue(message = "capture-timeout must be longer than max-video-duration")
        public boolean isCaptureTimeoutLongEnough() {
            return captureTimeout == null || maxVideoDuration == null || captureTimeout.compareTo(maxVideoDuration) > 0;
        }
    }
}
