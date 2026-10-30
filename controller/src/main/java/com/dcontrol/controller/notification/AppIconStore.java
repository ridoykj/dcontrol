package com.dcontrol.controller.notification;

import com.dcontrol.controller.common.config.AppProperties;
import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.common.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * App icons phones upload for mirrored notifications, cached on disk per device:
 * {@code <storage.dir>/icons/<deviceKey>/<sha256>.png}. Icons are content-addressed, so they never change.
 */
@Component
public class AppIconStore {

    public static final int MAX_BYTES = 32 * 1024;
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private final Path dir;

    public AppIconStore(AppProperties properties) {
        this.dir = Path.of(properties.storage().dir()).resolve("icons");
    }

    public boolean exists(UUID deviceKey, String hash) {
        return isHash(hash) && Files.exists(path(deviceKey, hash));
    }

    public Optional<byte[]> read(UUID deviceKey, String hash) {
        if (!isHash(hash)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(path(deviceKey, hash)));
        } catch (java.nio.file.NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Validates (PNG, size, hash) and stores the icon; storing an existing icon again is a no-op. */
    public void store(UUID deviceKey, String hash, InputStream body) {
        if (!isHash(hash)) {
            throw ApiException.validation("Icon hash must be 64 lowercase hex characters");
        }
        byte[] bytes;
        try {
            bytes = body.readNBytes(MAX_BYTES + 1);
        } catch (IOException e) {
            throw ApiException.validation("Upload interrupted");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE,
                    "Icon exceeds " + MAX_BYTES / 1024 + " KiB");
        }
        if (bytes.length < PNG_SIGNATURE.length
                || !Arrays.equals(bytes, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) {
            throw ApiException.validation("Icon must be a PNG image");
        }
        if (!sha256(bytes).equals(hash)) {
            throw ApiException.validation("Icon hash does not match its content");
        }
        Path target = path(deviceKey, hash);
        if (Files.exists(target)) {
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), hash, ".tmp");
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path path(UUID deviceKey, String hash) {
        return dir.resolve(deviceKey.toString()).resolve(hash + ".png");
    }

    private static boolean isHash(String hash) {
        return hash != null && HASH.matcher(hash).matches();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
