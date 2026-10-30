package com.dcontrol.controller.pairing;

import com.dcontrol.controller.auth.User;
import com.dcontrol.controller.auth.UserRepository;
import com.dcontrol.controller.common.config.AppProperties;
import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.context.ActorContext;
import com.dcontrol.controller.common.context.EventChannel;
import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.common.error.ErrorCode;
import com.dcontrol.controller.common.persistence.ActorTx;
import com.dcontrol.controller.common.security.RateLimiter;
import com.dcontrol.controller.common.security.SecureTokens;
import com.dcontrol.controller.device.Device;
import com.dcontrol.controller.device.DeviceAction;
import com.dcontrol.controller.device.DeviceEvents;
import com.dcontrol.controller.device.DeviceFsm;
import com.dcontrol.controller.device.DeviceRepository;
import com.dcontrol.controller.pairing.PairingDtos.ClaimRequest;
import com.dcontrol.controller.pairing.PairingDtos.ClaimResponse;
import com.dcontrol.controller.pairing.PairingDtos.PairingResponse;
import com.dcontrol.controller.pairing.PairingDtos.PreviewResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * QR pairing. The web UI issues a short-lived token; the phone previews it (to show the user which account
 * it joins) and claims it, receiving a device id and secret. The claim is attributed to the user who
 * issued the token.
 */
@Service
public class PairingService {

    private final PairingTokenRepository tokens;
    private final DeviceRepository devices;
    private final UserRepository users;
    private final ActorTx actorTx;
    private final RateLimiter rateLimiter;
    private final ApplicationEventPublisher events;
    private final AppProperties properties;
    private final Clock clock;

    public PairingService(PairingTokenRepository tokens, DeviceRepository devices, UserRepository users,
                          ActorTx actorTx, RateLimiter rateLimiter, ApplicationEventPublisher events,
                          AppProperties properties, Clock clock) {
        this.tokens = tokens;
        this.devices = devices;
        this.users = users;
        this.actorTx = actorTx;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public PairingResponse create() {
        rateLimiter.check("pairing-create:" + ActorContext.current().subjectKey(), 10, Duration.ofMinutes(1));
        String raw = SecureTokens.random(32);
        PairingToken token = new PairingToken(SecureTokens.sha256Hex(raw),
                clock.instant().plus(properties.pairing().tokenTtl()));
        PairingFsm.MACHINE.move(token, PairingAction.ISSUE);
        PairingToken saved = tokens.saveAndFlush(token);
        return new PairingResponse(saved.getId(), saved.getState(), saved.getExpiresAt(), null, qrPayload(raw));
    }

    @Transactional(readOnly = true)
    public PairingResponse get(UUID id) {
        PairingToken token = find(id);
        return new PairingResponse(token.getId(), token.getState(), token.getExpiresAt(), token.getDeviceKey(), null);
    }

    @Transactional
    public void cancel(UUID id) {
        PairingToken token = find(id);
        PairingFsm.MACHINE.move(token, PairingAction.CANCEL);
        tokens.saveAndFlush(token);
    }

    public PreviewResponse preview(String rawToken, String clientIp) {
        rateLimiter.check("pairing-device:" + clientIp, 30, Duration.ofMinutes(1));
        PairingToken token = claimable(rawToken);
        String email = actorTx.read(system("pairing.preview"), () ->
                users.findByIdAndActive(token.getCreatedBy(), true).map(User::getEmail).orElseThrow(this::invalid));
        return new PreviewResponse(maskEmail(email), token.getExpiresAt());
    }

    public ClaimResponse claim(ClaimRequest request, String clientIp) {
        rateLimiter.check("pairing-device:" + clientIp, 30, Duration.ofMinutes(1));
        PairingToken found = claimable(request.token());
        String secret = SecureTokens.random(32);
        Instant now = clock.instant();
        Actor issuer = Actor.user(found.getAccountKey(), found.getCreatedBy(), EventChannel.HTTP);
        try {
            return actorTx.write(issuer, () -> {
                PairingToken token = tokens.findByIdAndActive(found.getId(), true).orElseThrow(this::invalid);
                if (!token.isClaimable(now)) {
                    throw invalid();
                }
                Device device = new Device(request.device().name().strip(), request.device().info(),
                        SecureTokens.sha256Hex(secret), now);
                DeviceFsm.MACHINE.move(device, DeviceAction.PAIR);
                Device saved = devices.saveAndFlush(device);
                token.attachDevice(saved.getId());
                PairingFsm.MACHINE.move(token, PairingAction.CLAIM);
                tokens.saveAndFlush(token);
                events.publishEvent(new DeviceEvents.PairingCompleted(saved.getAccountKey(), token.getId(), saved.getId()));
                return new ClaimResponse(saved.getId(), secret, saved.getName());
            });
        } catch (ObjectOptimisticLockingFailureException e) {
            throw invalid();
        }
    }

    private PairingToken claimable(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 100) {
            throw invalid();
        }
        Instant now = clock.instant();
        PairingToken token = actorTx.read(system("pairing.lookup"),
                () -> tokens.findByTokenHash(SecureTokens.sha256Hex(rawToken)).orElse(null));
        if (token == null || !token.isClaimable(now)) {
            throw invalid();
        }
        return token;
    }

    private PairingToken find(UUID id) {
        return tokens.findByIdAndActive(id, true).orElseThrow(() -> ApiException.notFound("Pairing"));
    }

    private String qrPayload(String rawToken) {
        return "dcontrol://pair?v=1&u=" + URLEncoder.encode(properties.publicBaseUrl(), StandardCharsets.UTF_8)
                + "&t=" + rawToken;
    }

    static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static Actor system(String purpose) {
        return Actor.system(purpose, EventChannel.HTTP);
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.GONE, ErrorCode.PAIRING_TOKEN_INVALID, "Pairing token is invalid or expired");
    }
}
