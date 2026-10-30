package com.dcontrol.controller.common.security;

import com.dcontrol.controller.common.config.AppProperties;
import com.dcontrol.controller.common.context.ActorContext;
import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/** Issues HS256 tokens: short-lived access tokens and purpose-bound email tokens. */
@Component
public class JwtService {

    public static final String ISSUER = "dcontrol";
    public static final String TYPE_CLAIM = "typ";

    private final JwtEncoder encoder;
    private final AppProperties properties;
    private final Clock clock;
    private final Map<TokenType, JwtDecoder> decoders = new EnumMap<>(TokenType.class);

    public JwtService(JwtEncoder encoder, SecretKey jwtKey, AppProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
        for (TokenType type : TokenType.values()) {
            decoders.put(type, decoder(jwtKey, type));
        }
    }

    /** Decoder that accepts only tokens of the given type issued by this service. */
    public static JwtDecoder decoder(SecretKey key, TokenType type) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), typeValidator(type)));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> typeValidator(TokenType type) {
        OAuth2Error error = new OAuth2Error("invalid_token", "Unexpected token type", null);
        return jwt -> type.claim().equals(jwt.getClaimAsString(TYPE_CLAIM))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(error);
    }

    public String accessToken(UUID userKey, UUID accountKey) {
        return encode(TokenType.ACCESS, userKey, properties.jwt().accessTokenTtl(),
                Map.of(ActorContext.ACCOUNT_CLAIM, accountKey.toString()));
    }

    public String emailToken(TokenType type, UUID userKey, Duration ttl, Map<String, Object> claims) {
        if (type == TokenType.ACCESS) {
            throw new IllegalArgumentException("Use accessToken()");
        }
        return encode(type, userKey, ttl, claims);
    }

    /** Validates signature, issuer, expiry and type; any failure is reported as {@code TOKEN_INVALID}. */
    public Jwt decode(TokenType type, String token) {
        try {
            return decoders.get(type).decode(token);
        } catch (JwtException e) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, ErrorCode.TOKEN_INVALID,
                    "The link is invalid or has expired");
        }
    }

    private String encode(TokenType type, UUID subject, Duration ttl, Map<String, Object> extraClaims) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subject.toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString())
                .claim(TYPE_CLAIM, type.claim());
        extraClaims.forEach(claims::claim);
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
