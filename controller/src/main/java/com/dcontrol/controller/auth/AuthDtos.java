package com.dcontrol.controller.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Request and response bodies of the auth endpoints. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 128) String password,
            @NotBlank @Size(max = 80) String displayName) {
    }

    public record RegisterResponse(String email) {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password) {
    }

    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record TokenRequest(@NotBlank @Size(max = 2048) String token) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(max = 2048) String token,
            @NotBlank @Size(min = 10, max = 128) String password) {
    }

    public record UserResponse(UUID id, UUID accountId, String email, String displayName) {

        static UserResponse of(User user) {
            return new UserResponse(user.getId(), user.getAccountKey(), user.getEmail(), user.getDisplayName());
        }
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
    }

    /** Internal result of login/refresh: the raw refresh token goes into the cookie, never the body. */
    public record Session(TokenResponse tokens, String refreshToken) {
    }
}
