package com.dcontrol.controller.transfer;

import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.common.error.ErrorCode;
import com.dcontrol.controller.transfer.ByteRanges.Slice;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Streams spool files. Content comes from phones (untrusted), so it is always sent as an opaque
 * attachment that browsers never render or sniff.
 */
final class ContentResponses {

    private ContentResponses() {
    }

    /** Streams the requested range; returns true when the response reached the end of the file. */
    static boolean stream(TransferSpool spool, FileTransfer transfer, String rangeHeader, boolean attachment,
                          HttpServletResponse response) throws IOException {
        long size = transfer.getSize();
        Optional<Slice> requested = ByteRanges.range(rangeHeader, size);
        if (requested.isPresent() && !ByteRanges.satisfiable(requested.get())) {
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
            throw new ApiException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, ErrorCode.VALIDATION,
                    "Range starts beyond the end of the file");
        }
        Slice slice = requested.orElse(new Slice(0, size - 1));
        long length = size == 0 ? 0 : slice.length();
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        if (attachment) {
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(safeFileName(transfer.getFileName()), StandardCharsets.UTF_8)
                    .build()
                    .toString());
        }
        if (requested.isPresent()) {
            response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE,
                    "bytes " + slice.start() + "-" + slice.end() + "/" + size);
        }
        response.setContentLengthLong(length);
        UUID id = transfer.getId();
        long copied = length == 0 ? 0 : spool.copy(id, slice.start(), length, response.getOutputStream());
        response.flushBuffer();
        return copied == length && (length == 0 || slice.end() == size - 1);
    }

    /** Removes control characters and path separators; keeps at most 255 characters. */
    static String safeFileName(String name) {
        String cleaned = name.replaceAll("[\\p{Cntrl}/\\\\]", "_").strip();
        if (cleaned.isEmpty()) {
            cleaned = "download";
        }
        return cleaned.length() <= 255 ? cleaned : cleaned.substring(0, 255);
    }
}
