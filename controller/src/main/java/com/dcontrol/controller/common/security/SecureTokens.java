package com.dcontrol.controller.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Random opaque tokens and their SHA-256 fingerprints. Only fingerprints are stored. */
public final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureTokens() {
    }

    /** Base64url (no padding) of {@code bytes} random bytes; 32 bytes give 43 characters. */
    public static String random(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    public static String sha256Hex(String value) {
        return HexFormat.of().formatHex(digest(value));
    }

    /** Constant-time comparison of a stored hex fingerprint with a presented raw token. */
    public static boolean matches(String storedHex, String presented) {
        if (storedHex == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(storedHex.getBytes(StandardCharsets.US_ASCII),
                sha256Hex(presented).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
