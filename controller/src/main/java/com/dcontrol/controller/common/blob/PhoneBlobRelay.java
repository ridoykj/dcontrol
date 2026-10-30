package com.dcontrol.controller.common.blob;

import com.dcontrol.controller.common.config.AppProperties;
import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.common.error.ErrorCode;
import com.dcontrol.controller.common.persistence.Uuid7;
import com.dcontrol.controller.common.ws.RelayException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Single-use, in-memory upload slots a phone fills on request, so small binaries (contact photos) reach a
 * waiting browser request without touching the WebSocket or the disk. Single node only.
 */
@Component
public class PhoneBlobRelay {

    /** An image a phone uploaded; {@code type} is derived from the magic bytes, never from the phone. */
    public record Blob(byte[] bytes, MediaType type) {
    }

    /** An open slot: hand {@code uploadId} to the phone, then wait on {@code blob}. */
    public record Slot(UUID uploadId, CompletableFuture<Blob> blob) {
    }

    private record Pending(UUID deviceKey, CompletableFuture<Blob> blob) {
    }

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};

    private final ConcurrentHashMap<UUID, Pending> slots = new ConcurrentHashMap<>();
    private final int maxBytes;
    private final Duration timeout;

    @Autowired
    public PhoneBlobRelay(AppProperties properties) {
        this((int) properties.blobs().maxSize().toBytes(), properties.blobs().timeout());
    }

    PhoneBlobRelay(int maxBytes, Duration timeout) {
        this.maxBytes = maxBytes;
        this.timeout = timeout;
    }

    /**
     * Opens a slot only {@code deviceKey} may fill. The future fails with a {@code TimeoutException} when
     * nothing arrives in time, or with a {@link RelayException}; the slot is gone once the future completes.
     */
    public Slot open(UUID deviceKey) {
        UUID uploadId = Uuid7.generate();
        CompletableFuture<Blob> blob = new CompletableFuture<Blob>().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        slots.put(uploadId, new Pending(deviceKey, blob));
        blob.whenComplete((value, error) -> slots.remove(uploadId));
        return new Slot(uploadId, blob);
    }

    /**
     * Fills a slot with the phone's upload. Unknown, used, expired and other devices' slots are 404; the
     * slot is used up even when the upload is rejected, so the waiting request fails at once.
     */
    public void fill(UUID deviceKey, UUID uploadId, InputStream body) {
        Pending pending = slots.get(uploadId);
        if (pending == null || !pending.deviceKey().equals(deviceKey) || !slots.remove(uploadId, pending)) {
            throw ApiException.notFound("Upload slot");
        }
        byte[] bytes;
        try {
            bytes = body.readNBytes(maxBytes + 1);
        } catch (IOException e) {
            reject(pending, ApiException.validation("Upload interrupted"));
            return;
        }
        if (bytes.length > maxBytes) {
            reject(pending, new ApiException(HttpStatus.CONTENT_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE,
                    "Upload exceeds " + maxBytes / 1024 + " KiB"));
            return;
        }
        MediaType type = startsWith(bytes, PNG) ? MediaType.IMAGE_PNG
                : startsWith(bytes, JPEG) ? MediaType.IMAGE_JPEG
                : null;
        if (type == null) {
            reject(pending, new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.VALIDATION,
                    "Upload must be a JPEG or PNG image"));
            return;
        }
        pending.blob().complete(new Blob(bytes, type));
    }

    /** Fails a slot, e.g. when the phone answered the command with an error. */
    public void fail(UUID uploadId, RelayException error) {
        Pending pending = slots.remove(uploadId);
        if (pending != null) {
            pending.blob().completeExceptionally(error);
        }
    }

    int openSlots() {
        return slots.size();
    }

    private static void reject(Pending pending, ApiException rejection) {
        pending.blob().completeExceptionally(new RelayException(ErrorCode.INTERNAL, "Invalid device upload"));
        throw rejection;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }
}
