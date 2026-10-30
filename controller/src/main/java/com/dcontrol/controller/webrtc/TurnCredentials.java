package com.dcontrol.controller.webrtc;

import com.dcontrol.controller.common.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Short-lived TURN credentials in coturn's {@code use-auth-secret} scheme (the "TURN REST API"): the username is
 * {@code <unix expiry>:<user id>} and the password {@code base64(HMAC-SHA1(secret, username))}. Never log them.
 */
@Component
public class TurnCredentials {

    public record Credentials(String username, String credential) {
    }

    private final AppProperties.Webrtc webrtc;
    private final Clock clock;

    public TurnCredentials(AppProperties properties, Clock clock) {
        this.webrtc = properties.webrtc();
        this.clock = clock;
    }

    public Credentials issue(UUID userKey) {
        long expiry = clock.instant().plus(webrtc.credentialTtl()).getEpochSecond();
        String username = expiry + ":" + userKey;
        return new Credentials(username, sign(webrtc.turnSecret(), username));
    }

    static String sign(String secret, String username) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }
}
