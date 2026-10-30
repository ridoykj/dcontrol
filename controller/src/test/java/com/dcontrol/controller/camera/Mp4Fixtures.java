package com.dcontrol.controller.camera;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** The smallest files the camera spool accepts: a JPEG start, and an MP4 with an {@code mvhd} length. */
final class Mp4Fixtures {

    static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 16, 'J', 'F', 'I', 'F', 0};

    private Mp4Fixtures() {
    }

    /** {@code ftyp}, an {@code mdat} the reader has to skip, then {@code moov/mvhd} (version 0 or 1). */
    static byte[] mp4(long durationMs, int version) {
        long timescale = 90_000;
        long duration = durationMs * timescale / 1000;
        int mvhdBody = version == 1 ? 112 : 100;
        ByteBuffer file = ByteBuffer.allocate(16 + 24 + 8 + 8 + mvhdBody);
        box(file, 16, "ftyp");
        file.put("isom".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x200);
        box(file, 24, "mdat");
        file.put(new byte[16]);
        box(file, 8 + 8 + mvhdBody, "moov");
        box(file, 8 + mvhdBody, "mvhd");
        file.put((byte) version).put(new byte[3]);
        if (version == 1) {
            file.putLong(0).putLong(0).putInt((int) timescale).putLong(duration);
        } else {
            file.putInt(0).putInt(0).putInt((int) timescale).putInt((int) duration);
        }
        file.put(new byte[file.remaining()]);
        return file.array();
    }

    private static void box(ByteBuffer file, int size, String type) {
        file.putInt(size).put(type.getBytes(StandardCharsets.ISO_8859_1));
    }
}
