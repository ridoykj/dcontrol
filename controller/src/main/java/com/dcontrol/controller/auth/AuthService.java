package com.dcontrol.controller.auth;

import com.dcontrol.controller.auth.AuthDtos.LoginRequest;
import com.dcontrol.controller.auth.AuthDtos.RegisterRequest;
import com.dcontrol.controller.auth.AuthDtos.RegisterResponse;
import com.dcontrol.controller.auth.AuthDtos.ResetPasswordRequest;
import com.dcontrol.controller.auth.AuthDtos.Session;
import com.dcontrol.controller.auth.AuthDtos.TokenResponse;
import com.dcontrol.controller.auth.AuthDtos.UserResponse;
import com.dcontrol.controller.common.config.AppProperties;
import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.context.ActorContext;
import com.dcontrol.controller.common.context.ActorType;
import com.dcontrol.controller.common.context.EventChannel;
import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.common.error.ErrorCode;
import com.dcontrol.controller.common.persistence.ActorTx;
import com.dcontrol.controller.common.persistence.Uuid7;
import com.dcontrol.controller.common.security.JwtService;
import com.dcontrol.controller.common.security.RateLimiter;
import com.dcontrol.controller.common.security.SecureTokens;
import com.dcontrol.controller.common.security.TokenType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Registration, email verification, login, refresh-token rotation and password reset.
 * Lookups before authentication run as the system actor; writes run as the affected user.
 */
@Service
public class AuthService {

    static final String EMAIL_CLAIM = "eml";
    static final String PASSWORD_FINGERPRINT_CLAIM = "pwf";

    /** A token reused within this window is treated as a race between tabs, not as theft. */
    private static final Duration REUSE_GRACE = Duration.ofSeconds(10);

    private final UserRepository users;
    private final AccountRepository accounts;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailService mailService;
    private final RateLimiter rateLimiter;
    private final ActorTx actorTx;
    private final AppProperties properties;
    private final Clock clock;
    private final String timingDummyHash;

    public AuthService(UserRepository users, AccountRepository accounts, RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwordEncoder, JwtService jwtService, MailService mailService,
                       RateLimiter rateLimiter, ActorTx actorTx, AppProperties properties, Clock clock) {
        this.users = users;
        this.accounts = accounts;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.mailService = mailService;
        this.rateLimiter = rateLimiter;
        this.actorTx = actorTx;
        this.properties = properties;
        this.clock = clock;
        this.timingDummyHash = passwordEncoder.encode(SecureTokens.random(16));
    }

    public RegisterResponse register(RegisterRequest request, String clientIp) {
        rateLimiter.check("register:" + clientIp, 10, Duration.ofHours(1));
        String email = normalize(request.email());
        String displayName = request.displayName().strip();
        String hash = passwordEncoder.encode(request.password());
        User user = actorTx.write(system("auth.register"), () -> {
            if (users.findActiveByEmail(email).isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.EMAIL_TAKEN,
                        "An account with this email already exists");
            }
            Account account = accounts.saveAndFlush(new Account(displayName));
            User created = users.saveAndFlush(User.owner(account.getId(), email, hash, displayName));
            mailService.queueVerification(created.getEmail(), created.getDisplayName(), verificationToken(created));
            return created;
        });
        return new RegisterResponse(user.getEmail());
    }

    public void verifyEmail(String token) {
        Jwt jwt = jwtService.decode(TokenType.EMAIL_VERIFY, token);
        String email = jwt.getClaimAsString(EMAIL_CLAIM);
        asUser(UUID.fromString(jwt.getSubject()), user -> {
            if (email == null || !user.getEmail().equalsIgnoreCase(email)) {
                throw tokenInvalid();
            }
            user.markEmailVerified(clock.instant());
        });
    }

    /** Always succeeds so the response doesn't reveal whether the email is registered. */
    public void resendVerification(String rawEmail, String clientIp) {
        String email = normalize(rawEmail);
        rateLimiter.check("resend-ip:" + clientIp, 10, Duration.ofHours(1));
        if (!rateLimiter.tryAcquire("resend-email:" + email, 3, Duration.ofHours(1))) {
            return;
        }
        User user = actorTx.read(system("auth.resend"), () -> users.findActiveByEmail(email).orElse(null));
        if (user != null && user.getRole() == UserRole.OWNER && !user.isEmailVerified()) {
            mailService.queueVerification(user.getEmail(), user.getDisplayName(), verificationToken(user));
        }
    }

    public Session login(LoginRequest request, String clientIp) {
        String email = normalize(request.email());
        rateLimiter.check("login-ip:" + clientIp, 30, Duration.ofMinutes(10));
        rateLimiter.check("login-email:" + email, 10, Duration.ofMinutes(15));
        User user = actorTx.read(system("auth.login"), () -> users.findActiveByEmail(email).orElse(null));
        boolean valid;
        if (user == null) {
            passwordEncoder.matches(request.password(), timingDummyHash);
            valid = false;
        } else {
            valid = user.getRole() == UserRole.OWNER && passwordEncoder.matches(request.password(), user.getPasswordHash());
        }
        if (!valid) {
            throw ApiException.unauthorized(ErrorCode.INVALID_CREDENTIALS, "Email or password is incorrect");
        }
        if (!user.isEmailVerified()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.EMAIL_NOT_VERIFIED,
                    "Verify your email address before signing in");
        }
        return startSession(user, Uuid7.generate());
    }

    public Session refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw sessionExpired();
        }
        Instant now = clock.instant();
        RefreshToken presented = actorTx.read(system("auth.refresh"),
                () -> refreshTokens.findByTokenHash(SecureTokens.sha256Hex(rawToken)).orElse(null));
        if (presented == null || !presented.isActive() || !now.isBefore(presented.getExpiresAt())) {
            throw sessionExpired();
        }
        Actor actor = Actor.user(presented.getAccountKey(), presented.getUserKey(), EventChannel.HTTP);
        if (presented.getUsedAt() != null) {
            if (presented.getUsedAt().plus(REUSE_GRACE).isBefore(now)) {
                actorTx.write(actor, () -> refreshTokens.deactivateFamily(presented.getFamilyKey()));
            }
            throw sessionExpired();
        }
        String nextRaw = SecureTokens.random(32);
        User user;
        try {
            user = actorTx.write(actor, () -> {
                RefreshToken current = refreshTokens.findByIdAndActive(presented.getId(), true)
                        .orElseThrow(this::sessionExpired);
                if (current.getUsedAt() != null) {
                    throw sessionExpired();
                }
                current.markUsed(now);
                refreshTokens.saveAndFlush(current);
                User owner = users.findByIdAndActive(current.getUserKey(), true).orElseThrow(this::sessionExpired);
                refreshTokens.save(new RefreshToken(owner.getId(), current.getFamilyKey(),
                        SecureTokens.sha256Hex(nextRaw), now.plus(properties.jwt().refreshTokenTtl())));
                return owner;
            });
        } catch (ObjectOptimisticLockingFailureException e) {
            throw sessionExpired();
        }
        return new Session(tokens(user), nextRaw);
    }

    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        RefreshToken presented = actorTx.read(system("auth.logout"),
                () -> refreshTokens.findByTokenHash(SecureTokens.sha256Hex(rawToken)).orElse(null));
        if (presented != null && presented.isActive()) {
            actorTx.write(Actor.user(presented.getAccountKey(), presented.getUserKey(), EventChannel.HTTP),
                    () -> refreshTokens.deactivateFamily(presented.getFamilyKey()));
        }
    }

    /** Always succeeds so the response doesn't reveal whether the email is registered. */
    public void forgotPassword(String rawEmail, String clientIp) {
        String email = normalize(rawEmail);
        rateLimiter.check("forgot-ip:" + clientIp, 10, Duration.ofHours(1));
        if (!rateLimiter.tryAcquire("forgot-email:" + email, 3, Duration.ofHours(1))) {
            return;
        }
        User user = actorTx.read(system("auth.forgot"), () -> users.findActiveByEmail(email).orElse(null));
        if (user != null && user.getRole() == UserRole.OWNER) {
            String token = jwtService.emailToken(TokenType.PASSWORD_RESET, user.getId(),
                    properties.jwt().passwordResetTtl(),
                    Map.of(PASSWORD_FINGERPRINT_CLAIM, passwordFingerprint(user.getPasswordHash())));
            mailService.queuePasswordReset(user.getEmail(), user.getDisplayName(), token);
        }
    }

    /** The token embeds a fingerprint of the current hash, so it stops working once the password changes. */
    public void resetPassword(ResetPasswordRequest request) {
        Jwt jwt = jwtService.decode(TokenType.PASSWORD_RESET, request.token());
        String fingerprint = jwt.getClaimAsString(PASSWORD_FINGERPRINT_CLAIM);
        String newHash = passwordEncoder.encode(request.password());
        asUser(UUID.fromString(jwt.getSubject()), user -> {
            if (fingerprint == null || !fingerprint.equals(passwordFingerprint(user.getPasswordHash()))) {
                throw tokenInvalid();
            }
            user.setPasswordHash(newHash);
            user.markEmailVerified(clock.instant());
            users.saveAndFlush(user);
            refreshTokens.deactivateAllOfUser(user.getId());
        });
    }

    public UserResponse me() {
        Actor actor = ActorContext.current();
        if (actor.type() != ActorType.USER) {
            throw ApiException.unauthorized(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return actorTx.read(actor, () -> users.findByIdAndActive(actor.subjectKey(), true)
                .map(UserResponse::of)
                .orElseThrow(() -> ApiException.unauthorized(ErrorCode.UNAUTHORIZED, "Account no longer exists")));
    }

    private Session startSession(User user, UUID familyKey) {
        String raw = SecureTokens.random(32);
        Instant expires = clock.instant().plus(properties.jwt().refreshTokenTtl());
        actorTx.write(Actor.user(user.getAccountKey(), user.getId(), EventChannel.HTTP),
                () -> refreshTokens.save(new RefreshToken(user.getId(), familyKey, SecureTokens.sha256Hex(raw), expires)));
        return new Session(tokens(user), raw);
    }

    private TokenResponse tokens(User user) {
        return new TokenResponse(jwtService.accessToken(user.getId(), user.getAccountKey()), "Bearer",
                properties.jwt().accessTokenTtl().toSeconds(), UserResponse.of(user));
    }

    private String verificationToken(User user) {
        return jwtService.emailToken(TokenType.EMAIL_VERIFY, user.getId(), properties.jwt().emailVerificationTtl(),
                Map.of(EMAIL_CLAIM, user.getEmail().toLowerCase(Locale.ROOT)));
    }

    /** Loads the user as system, then runs {@code work} in a transaction attributed to that user. */
    private void asUser(UUID userKey, Consumer<User> work) {
        User found = actorTx.read(system("auth.lookup"), () -> users.findByIdAndActive(userKey, true).orElse(null));
        if (found == null) {
            throw tokenInvalid();
        }
        actorTx.writeVoid(Actor.user(found.getAccountKey(), found.getId(), EventChannel.HTTP), () ->
                work.accept(users.findByIdAndActive(userKey, true).orElseThrow(this::tokenInvalid)));
    }

    static String passwordFingerprint(String passwordHash) {
        return SecureTokens.sha256Hex(passwordHash).substring(0, 32);
    }

    static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static Actor system(String purpose) {
        return Actor.system(purpose, EventChannel.HTTP);
    }

    private ApiException tokenInvalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.TOKEN_INVALID, "The link is invalid or has expired");
    }

    private ApiException sessionExpired() {
        return ApiException.unauthorized(ErrorCode.TOKEN_INVALID, "Session expired, sign in again");
    }
}
