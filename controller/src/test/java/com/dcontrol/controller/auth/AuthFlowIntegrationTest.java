package com.dcontrol.controller.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.support.Api;
import com.dcontrol.controller.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthFlowIntegrationTest extends IntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Test
    void registerVerifyLoginAndReadProfile() {
        UserSession session = newUser("flow");

        Api.Response me = api.get("/api/me", session.accessToken());
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().path("email").asString()).isEqualTo(session.email());
        assertThat(me.body().path("accountId").asString()).isEqualTo(session.accountId().toString());
    }

    @Test
    void loginIsRefusedUntilEmailIsVerified() {
        String email = uniqueEmail("unverified");
        register(email, PASSWORD);

        Api.Response login = api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
        assertThat(login.status()).isEqualTo(403);
        assertThat(login.code()).isEqualTo("EMAIL_NOT_VERIFIED");
    }

    @Test
    void duplicateRegistrationConflicts() {
        String email = uniqueEmail("dup");
        assertThat(register(email, PASSWORD).status()).isEqualTo(202);

        Api.Response again = register(email.toUpperCase(), PASSWORD);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void invalidRegistrationReturnsFieldErrors() {
        Api.Response response = api.post("/api/auth/register",
                Map.of("email", "not-an-email", "password", "short", "displayName", ""));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION");
        assertThat(response.body().path("errors").findValuesAsString("field"))
                .contains("email", "password", "displayName");
        assertThat(response.body().path("traceId").asString()).isNotBlank();
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
    }

    @Test
    void wrongPasswordIsRejectedGenerically() {
        UserSession session = newUser("wrongpw");
        Api.Response login = api.post("/api/auth/login", Map.of("email", session.email(), "password", "nope-nope-nope"));
        assertThat(login.status()).isEqualTo(401);
        assertThat(login.code()).isEqualTo("INVALID_CREDENTIALS");

        Api.Response unknown = api.post("/api/auth/login",
                Map.of("email", uniqueEmail("ghost"), "password", PASSWORD));
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(unknown.code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void protectedEndpointsRequireAValidAccessToken() {
        assertThat(api.get("/api/me", null).status()).isEqualTo(401);
        Api.Response forged = api.get("/api/me", "eyJhbGciOiJIUzI1NiJ9.e30.invalid");
        assertThat(forged.status()).isEqualTo(401);
        assertThat(forged.code()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void refreshRotatesAndDetectsReuse() {
        UserSession session = newUser("refresh");

        Api.Response first = refresh(session.refreshToken());
        assertThat(first.status()).isEqualTo(200);
        String rotated = first.cookie("dc_refresh").orElseThrow();
        assertThat(rotated).isNotEqualTo(session.refreshToken());
        assertThat(api.get("/api/me", first.body().path("accessToken").asString()).status()).isEqualTo(200);

        clock.advance(java.time.Duration.ofSeconds(30));
        Api.Response reuse = refresh(session.refreshToken());
        assertThat(reuse.status()).isEqualTo(401);

        // Reuse outside the grace window revoked the whole family, including the rotated token.
        assertThat(refresh(rotated).status()).isEqualTo(401);
    }

    @Test
    void refreshRequiresTheCsrfHeader() {
        UserSession session = newUser("csrf");
        Api.Response response = api.send("POST", "/api/auth/refresh", null,
                Map.of("Cookie", "dc_refresh=" + session.refreshToken()));
        assertThat(response.status()).isEqualTo(403);
    }

    @Test
    void logoutRevokesTheRefreshToken() {
        UserSession session = newUser("logout");
        Api.Response logout = api.send("POST", "/api/auth/logout", null,
                Map.of("Cookie", "dc_refresh=" + session.refreshToken(), "X-Requested-With", "XMLHttpRequest"));
        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.headers().allValues("Set-Cookie")).anyMatch(c -> c.contains("Max-Age=0"));
        assertThat(refresh(session.refreshToken()).status()).isEqualTo(401);
    }

    @Test
    void passwordResetWorksOnceAndRevokesSessions() {
        UserSession session = newUser("reset");
        assertThat(api.post("/api/auth/forgot-password", Map.of("email", session.email())).status()).isEqualTo(202);
        String token = mailpit.awaitToken(session.email(), "Reset");

        Api.Response reset = api.post("/api/auth/reset-password",
                Map.of("token", token, "password", "another-long-password"));
        assertThat(reset.status()).isEqualTo(204);

        Api.Response second = api.post("/api/auth/reset-password",
                Map.of("token", token, "password", "yet-another-password"));
        assertThat(second.status()).isEqualTo(400);
        assertThat(second.code()).isEqualTo("TOKEN_INVALID");

        assertThat(refresh(session.refreshToken()).status()).isEqualTo(401);
        login(session.email(), "another-long-password");
    }

    @Test
    void forgotPasswordDoesNotRevealUnknownEmails() {
        assertThat(api.post("/api/auth/forgot-password", Map.of("email", uniqueEmail("nobody"))).status())
                .isEqualTo(202);
    }

    @Test
    void verificationTokenCannotBeUsedAsAccessToken() {
        String email = uniqueEmail("typ");
        register(email, PASSWORD);
        String verifyToken = mailpit.awaitToken(email, "Verify");
        assertThat(api.get("/api/me", verifyToken).status()).isEqualTo(401);
    }

    @Test
    void loginIsRateLimited() {
        UserSession session = newUser("limited");
        Api.Response last = null;
        for (int i = 0; i < 11; i++) {
            last = api.post("/api/auth/login", Map.of("email", session.email(), "password", "wrong-password!"));
        }
        assertThat(last.status()).isEqualTo(429);
        assertThat(last.code()).isEqualTo("RATE_LIMITED");
    }

    private Api.Response refresh(String refreshToken) {
        return api.send("POST", "/api/auth/refresh", null,
                Map.of("Cookie", "dc_refresh=" + refreshToken, "X-Requested-With", "XMLHttpRequest"));
    }
}
