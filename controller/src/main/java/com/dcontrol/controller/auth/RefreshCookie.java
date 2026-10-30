package com.dcontrol.controller.auth;

import com.dcontrol.controller.common.config.AppProperties;
import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh token lives in an HttpOnly, SameSite=Strict cookie scoped to /api/auth. Endpoints that read it
 * also require {@code X-Requested-With}, which cross-site forms cannot set.
 */
@Component
public class RefreshCookie {

    public static final String NAME = "dc_refresh";
    public static final String CSRF_HEADER = "X-Requested-With";
    private static final String PATH = "/api/auth";

    private final AppProperties properties;

    public RefreshCookie(AppProperties properties) {
        this.properties = properties;
    }

    public ResponseCookie issue(String refreshToken) {
        return build(refreshToken, properties.jwt().refreshTokenTtl());
    }

    public ResponseCookie clear() {
        return build("", Duration.ZERO);
    }

    public void requireCsrfHeader(HttpServletRequest request) {
        if (request.getHeader(CSRF_HEADER) == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Missing " + CSRF_HEADER + " header");
        }
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(properties.cookie().secure())
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }
}
