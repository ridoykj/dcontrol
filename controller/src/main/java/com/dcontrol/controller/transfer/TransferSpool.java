package com.dcontrol.controller.transfer;

import com.dcontrol.controller.common.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Transfer contents on local disk: {@code <storage.dir>/transfers/<transferId>.bin}. */
@Component
public class TransferSpool {

    private static final int BUFFER = 64 * 1024;

    private final Path dir;

    public TransferSpool(AppProperties properties) {
        this.dir = Path.of(properties.storage().dir()).resolve("transfers");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create transfer spool " + dir, e);
        }
    }

    public Path path(UUID transferId) {
        return dir.resolve(transferId + ".bin");
    }

    public void create(UUID transferId) {
        try {
            Files.newByteChannel(path(transferId), StandardOpenOption.CREATE, StandardOpenOption.WRITE).close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes up to {@code length} bytes from {@code body} at {@code offset}, discarding anything previously
     * written past the offset. Returns the number of bytes the body actually had (reading stops at
     * {@code length + 1}). When that differs from {@code length}, or reading fails, the file is cut back to
     * {@code offset} so a failed chunk leaves no trace.
     */
    public long write(UUID transferId, long offset, InputStream body, long length) throws IOException {
        try (FileChannel channel = FileChannel.open(path(transferId), StandardOpenOption.WRITE,
                StandardOpenOption.CREATE)) {
            channel.truncate(offset);
            channel.position(offset);
            byte[] buffer = new byte[BUFFER];
            long written = 0;
            boolean ok = false;
            try {
                int read;
                while (written <= length && (read = body.read(buffer, 0,
                        (int) Math.min(buffer.length, length + 1 - written))) != -1) {
                    channel.write(ByteBuffer.wrap(buffer, 0, read));
                    written += read;
                }
                ok = written == length;
                return written;
            } finally {
                if (!ok) {
                    channel.truncate(offset);
                }
            }
        }
    }

    public String sha256(UUID transferId) {
        try (InputStream in = Files.newInputStream(path(transferId))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Copies {@code length} bytes starting at {@code start} to {@code out}; returns the bytes copied. */
    public long copy(UUID transferId, long start, long length, OutputStream out) throws IOException {
        try (FileChannel channel = FileChannel.open(path(transferId), StandardOpenOption.READ)) {
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER);
            long copied = 0;
            while (copied < length) {
                buffer.clear();
                buffer.limit((int) Math.min(BUFFER, length - copied));
                int read = channel.read(buffer);
                if (read == -1) {
                    break;
                }
                out.write(buffer.array(), 0, read);
                copied += read;
            }
            return copied;
        }
    }

    public boolean exists(UUID transferId) {
        return Files.exists(path(transferId));
    }

    public void delete(UUID transferId) {
        try {
            Files.deleteIfExists(path(transferId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
