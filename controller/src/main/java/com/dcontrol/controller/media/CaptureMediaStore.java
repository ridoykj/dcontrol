package com.dcontrol.controller.media;

import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.common.error.ErrorCode;
import com.dcontrol.controller.common.persistence.Uuid7;
import com.dcontrol.controller.common.ws.RelayException;
import com.dcontrol.controller.device.DeviceEvents;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Media phones upload during a capture session (camera photos and clips, microphone clips). Each capture gets a
 * single-use upload slot only its phone may fill; the type comes from the magic bytes and a clip's length from
 * the MP4, never from the phone. Accepted captures live in {@code <storage.dir>/<name>} and an in-memory index
 * for the feature's retention; nothing goes into the database. Single node only.
 *
 * <p>Each feature subclasses it as a Spring bean, which runs the sweep and the unpair cleanup.
 */
public abstract class CaptureMediaStore {

    private static final Logger log = LoggerFactory.getLogger(CaptureMediaStore.class);
    private static final int BUFFER = 64 * 1024;
    private static final String SUFFIX = ".bin";
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private static final byte[] FTYP = {'f', 't', 'y', 'p'};
    /* Encoders round the last frame, so a clip may run slightly over what was asked for. */
    private static final long CLIP_SLACK_MS = 1_000;

    /** How an upload is checked. */
    public enum Container { JPEG, MP4 }

    /**
     * What a slot accepts: the container checked by magic bytes, the size cap and, for MP4, the length cap.
     * {@code mime} is what the browser gets.
     */
    public record Format(String mime, Container container, long maxBytes, long maxMillis, String what) {
    }

    /** An accepted capture. */
    public record Media(UUID id, UUID deviceKey, String mime, long bytes, Long durationMs, Instant capturedAt) {
    }

    /** What a phone uploaded into a slot, as the controller measured it. */
    public record Upload(String mime, long bytes, Long durationMs) {
    }

    /** An open slot: hand {@code mediaId} to the phone. {@code upload} completes when its upload is accepted. */
    public record Slot(UUID mediaId, CompletableFuture<Upload> upload) {
    }

    private record Pending(UUID deviceKey, Format format, CompletableFuture<Upload> upload, AtomicBoolean used) {
    }

    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Media> media = new ConcurrentHashMap<>();
    private final Path dir;
    private final Clock clock;
    private final Duration slotTimeout;
    private final Duration retention;
    private final int maxRetained;

    protected CaptureMediaStore(Path dir, Clock clock, Duration slotTimeout, Duration retention, int maxRetained) {
        this.dir = dir;
        this.clock = clock;
        this.slotTimeout = slotTimeout;
        this.retention = retention;
        this.maxRetained = maxRetained;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create capture spool " + dir, e);
        }
        // The index is in memory, so files from an earlier run can never be served again.
        deleteOrphans();
    }

    /** Opens a slot for one capture of {@code deviceKey}. It expires with the capture command. */
    protected Slot open(UUID deviceKey, Format format) {
        UUID mediaId = Uuid7.generate();
        CompletableFuture<Upload> upload = new CompletableFuture<Upload>()
                .orTimeout(slotTimeout.toMillis(), TimeUnit.MILLISECONDS);
        pending.put(mediaId, new Pending(deviceKey, format, upload, new AtomicBoolean()));
        return new Slot(mediaId, upload);
    }

    /**
     * Fills a slot with the phone's upload. Unknown, used, expired and other devices' slots are 404; the slot is
     * used up even when the upload is rejected (413, 415), so the waiting capture fails at once.
     */
    public void fill(UUID deviceKey, UUID mediaId, InputStream body) {
        Pending slot = pending.get(mediaId);
        if (slot == null || !slot.deviceKey().equals(deviceKey) || slot.upload().isDone()
                || !slot.used().compareAndSet(false, true)) {
            throw ApiException.notFound("Upload slot");
        }
        Path file = path(mediaId);
        Upload upload;
        try {
            upload = store(slot.format(), body, file);
        } catch (ApiException rejected) {
            delete(file);
            slot.upload().completeExceptionally(new RelayException(ErrorCode.INTERNAL, "Invalid device upload"));
            throw rejected;
        } catch (IOException e) {
            delete(file);
            slot.upload().completeExceptionally(new RelayException(ErrorCode.INTERNAL, "Upload interrupted"));
            throw ApiException.validation("Upload interrupted");
        }
        if (!slot.upload().complete(upload) || pending.get(mediaId) != slot) {
            // The capture timed out or ended while the bytes were arriving.
            delete(file);
            throw ApiException.notFound("Upload slot");
        }
    }

    /**
     * Ends a slot after the phone answered the capture successfully: the upload becomes a kept capture.
     * Fails with {@code INTERNAL} when the phone answered without a valid upload.
     */
    public Media keep(UUID mediaId, Instant capturedAt) {
        Pending slot = pending.remove(mediaId);
        if (slot == null) {
            throw new RelayException(ErrorCode.INTERNAL, "Capture slot expired");
        }
        Upload upload = slot.upload().isDone() && !slot.upload().isCompletedExceptionally()
                ? slot.upload().join()
                : null;
        if (upload == null) {
            slot.upload().completeExceptionally(new RelayException(ErrorCode.INTERNAL, "Device sent no capture"));
            delete(path(mediaId));
            throw new RelayException(ErrorCode.INTERNAL, "Device sent no capture");
        }
        Media kept = new Media(mediaId, slot.deviceKey(), upload.mime(), upload.bytes(), upload.durationMs(),
                capturedAt);
        synchronized (media) {
            media.put(mediaId, kept);
            List<Media> ofDevice = media.values().stream()
                    .filter(item -> item.deviceKey().equals(kept.deviceKey()))
                    .sorted(Comparator.comparing(Media::capturedAt).thenComparing(Media::id))
                    .toList();
            ofDevice.subList(0, Math.max(0, ofDevice.size() - maxRetained)).forEach(this::forget);
        }
        return kept;
    }

    /** Ends a slot whose capture failed, deleting anything the phone already uploaded. */
    public void discard(UUID mediaId) {
        Pending slot = pending.remove(mediaId);
        if (slot != null) {
            slot.upload().completeExceptionally(new RelayException(ErrorCode.INTERNAL, "Capture failed"));
            delete(path(mediaId));
        }
    }

    /** A kept capture of {@code deviceKey}; the caller has checked the device belongs to the user. */
    public Optional<Media> find(UUID deviceKey, UUID mediaId) {
        Media found = media.get(mediaId);
        if (found == null || !found.deviceKey().equals(deviceKey) || expired(found, clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    public Path path(UUID mediaId) {
        return dir.resolve(mediaId + SUFFIX);
    }

    /** Removes expired captures, and files no slot or capture knows, e.g. one a reader kept open last time. */
    @Scheduled(fixedDelayString = "PT1M")
    public void sweep() {
        Instant now = clock.instant();
        synchronized (media) {
            media.values().stream().filter(item -> expired(item, now)).toList().forEach(this::forget);
        }
        deleteOrphans();
    }

    /** Unpairing removes the phone's captures, as it removes the rest of its data. */
    @EventListener
    public void onDeviceRevoked(DeviceEvents.DeviceRevoked event) {
        synchronized (media) {
            media.values().stream().filter(item -> item.deviceKey().equals(event.deviceKey())).toList()
                    .forEach(this::forget);
        }
        pending.entrySet().stream().filter(entry -> entry.getValue().deviceKey().equals(event.deviceKey()))
                .map(Map.Entry::getKey).toList().forEach(this::discard);
    }

    private boolean expired(Media item, Instant now) {
        return !item.capturedAt().plus(retention).isAfter(now);
    }

    private void forget(Media item) {
        media.remove(item.id());
        delete(path(item.id()));
    }

    /** Copies the body into {@code file} and checks it is what the slot was opened for. */
    private static Upload store(Format format, InputStream body, Path file) throws IOException {
        long max = format.maxBytes();
        long written = 0;
        try (OutputStream out = Files.newOutputStream(file)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = body.read(buffer)) != -1) {
                written += read;
                if (written > max) {
                    throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE,
                            "Upload exceeds " + max / (1024 * 1024) + " MiB");
                }
                out.write(buffer, 0, read);
            }
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer head = ByteBuffer.allocate(8);
            channel.read(head, 0);
            byte[] start = Arrays.copyOf(head.array(), head.position());
            if (format.container() == Container.JPEG) {
                if (!matches(start, 0, JPEG)) {
                    throw unsupported("A " + format.what() + " must be a JPEG image");
                }
                return new Upload(format.mime(), written, null);
            }
            if (!matches(start, 4, FTYP)) {
                throw unsupported("A " + format.what() + " must be an MP4 file");
            }
            OptionalLong millis = Mp4Duration.millis(channel);
            if (millis.isEmpty()) {
                throw unsupported("The " + format.what() + " has no readable length");
            }
            if (millis.getAsLong() > format.maxMillis() + CLIP_SLACK_MS) {
                throw unsupported("The " + format.what() + " is longer than " + format.maxMillis() / 1000 + " s");
            }
            return new Upload(format.mime(), written, millis.getAsLong());
        }
    }

    private static ApiException unsupported(String detail) {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.VALIDATION, detail);
    }

    private static boolean matches(byte[] bytes, int offset, byte[] expected) {
        return bytes.length >= offset + expected.length
                && Arrays.equals(bytes, offset, offset + expected.length, expected, 0, expected.length);
    }

    private void deleteOrphans() {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                UUID id;
                try {
                    id = UUID.fromString(name.substring(0, name.length() - SUFFIX.length()));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (!media.containsKey(id) && !pending.containsKey(id)) {
                    delete(file);
                }
            }
        } catch (IOException e) {
            log.warn("Cannot list capture spool {}", dir, e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Still open, e.g. a browser is reading it; the next sweep retries.
            log.debug("Cannot delete {} yet", file.getFileName());
        }
    }
}
