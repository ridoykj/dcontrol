package com.dcontrol.controller.transfer;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the {@code Content-Range} of chunk uploads and the {@code Range} of downloads. */
public final class ByteRanges {

    private static final Pattern CONTENT_RANGE = Pattern.compile("^bytes (\\d{1,19})-(\\d{1,19})/(\\d{1,19})$");
    private static final Pattern RANGE = Pattern.compile("^bytes=(\\d{1,19})-(\\d{0,19})$");

    /** {@code Content-Range: bytes start-end/total}, end inclusive. */
    public record ContentRange(long start, long end, long total) {

        public long length() {
            return end - start + 1;
        }
    }

    /** A satisfiable single range of a file of known size, end inclusive. */
    public record Slice(long start, long end) {

        public long length() {
            return end - start + 1;
        }
    }

    private ByteRanges() {
    }

    /** Empty when the header is missing or malformed, or describes no bytes. */
    public static Optional<ContentRange> contentRange(String header) {
        if (header == null) {
            return Optional.empty();
        }
        Matcher m = CONTENT_RANGE.matcher(header.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            long start = Long.parseLong(m.group(1));
            long end = Long.parseLong(m.group(2));
            long total = Long.parseLong(m.group(3));
            return end < start || end >= total ? Optional.empty() : Optional.of(new ContentRange(start, end, total));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Resolves a {@code Range} header against a file size. Empty means "send the whole file" (no header, or a
     * form we don't support such as multiple ranges); a {@link Slice} with start beyond the size means 416.
     */
    public static Optional<Slice> range(String header, long size) {
        if (header == null) {
            return Optional.empty();
        }
        Matcher m = RANGE.matcher(header.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            long start = Long.parseLong(m.group(1));
            long end = m.group(2).isEmpty() ? size - 1 : Math.min(Long.parseLong(m.group(2)), size - 1);
            if (start >= size) {
                return Optional.of(new Slice(start, start - 1));
            }
            return end < start ? Optional.empty() : Optional.of(new Slice(start, end));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static boolean satisfiable(Slice slice) {
        return slice.end() >= slice.start();
    }
}
