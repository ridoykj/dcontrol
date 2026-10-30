package com.dcontrol.controller.media;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

/**
 * Reads a clip's length from the {@code moov/mvhd} box of an MP4 (ISO/IEC 14496-12), so the controller never
 * relies on the length a phone claims. Only box headers and the {@code mvhd} fields are read.
 */
final class Mp4Duration {

    private static final int MAX_BOXES = 1_000;

    private Mp4Duration() {
    }

    /** The clip length in milliseconds, or empty when the file has no readable {@code mvhd}. */
    static OptionalLong millis(FileChannel file) throws IOException {
        Box moov = find(file, 0, file.size(), "moov");
        if (moov == null) {
            return OptionalLong.empty();
        }
        Box mvhd = find(file, moov.bodyStart(), moov.end(), "mvhd");
        if (mvhd == null) {
            return OptionalLong.empty();
        }
        ByteBuffer body = read(file, mvhd.bodyStart(), (int) Math.min(32, mvhd.end() - mvhd.bodyStart()));
        if (body.remaining() < 20) {
            return OptionalLong.empty();
        }
        int version = body.get(0) & 0xff;
        long timescale;
        long duration;
        if (version == 1) {
            if (body.remaining() < 32) {
                return OptionalLong.empty();
            }
            timescale = Integer.toUnsignedLong(body.getInt(20));
            duration = body.getLong(24);
        } else {
            timescale = Integer.toUnsignedLong(body.getInt(12));
            duration = Integer.toUnsignedLong(body.getInt(16));
        }
        if (timescale == 0 || duration < 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(duration / timescale * 1000 + duration % timescale * 1000 / timescale);
    }

    private record Box(long bodyStart, long end) {
    }

    private static Box find(FileChannel file, long from, long to, String type) throws IOException {
        long position = from;
        for (int i = 0; i < MAX_BOXES && position + 8 <= to; i++) {
            ByteBuffer header = read(file, position, 16);
            if (header.remaining() < 8) {
                return null;
            }
            long size = Integer.toUnsignedLong(header.getInt(0));
            String name = new String(new byte[] {header.get(4), header.get(5), header.get(6), header.get(7)},
                    StandardCharsets.ISO_8859_1);
            int headerLength = 8;
            if (size == 1) {
                if (header.remaining() < 16) {
                    return null;
                }
                size = header.getLong(8);
                headerLength = 16;
            } else if (size == 0) {
                size = to - position;
            }
            if (size < headerLength || position + size > to) {
                return null;
            }
            if (name.equals(type)) {
                return new Box(position + headerLength, position + size);
            }
            position += size;
        }
        return null;
    }

    private static ByteBuffer read(FileChannel file, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (file.read(buffer, position + buffer.position()) < 0) {
                break;
            }
        }
        return buffer.flip();
    }
}
