package com.dcontrol.controller.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.transfer.ByteRanges.ContentRange;
import com.dcontrol.controller.transfer.ByteRanges.Slice;
import org.junit.jupiter.api.Test;

class ByteRangesTest {

    @Test
    void parsesContentRange() {
        assertThat(ByteRanges.contentRange("bytes 0-9/100")).contains(new ContentRange(0, 9, 100));
        assertThat(ByteRanges.contentRange("bytes 0-9/100").orElseThrow().length()).isEqualTo(10);
        assertThat(ByteRanges.contentRange("bytes 99-99/100")).isPresent();
    }

    @Test
    void rejectsMalformedContentRange() {
        assertThat(ByteRanges.contentRange(null)).isEmpty();
        assertThat(ByteRanges.contentRange("bytes */100")).isEmpty();
        assertThat(ByteRanges.contentRange("bytes 10-9/100")).isEmpty();
        assertThat(ByteRanges.contentRange("bytes 0-100/100")).isEmpty();
        assertThat(ByteRanges.contentRange("bytes -1-5/100")).isEmpty();
        assertThat(ByteRanges.contentRange("bytes 0-99999999999999999999/1")).isEmpty();
    }

    @Test
    void resolvesRangeAgainstTheFileSize() {
        assertThat(ByteRanges.range(null, 10)).isEmpty();
        assertThat(ByteRanges.range("bytes=4-", 10)).contains(new Slice(4, 9));
        assertThat(ByteRanges.range("bytes=2-5", 10)).contains(new Slice(2, 5));
        assertThat(ByteRanges.range("bytes=2-500", 10)).contains(new Slice(2, 9));
        assertThat(ByteRanges.range("bytes=0-1,4-5", 10)).isEmpty();
        assertThat(ByteRanges.range("bytes=-5", 10)).isEmpty();
        Slice beyond = ByteRanges.range("bytes=10-", 10).orElseThrow();
        assertThat(ByteRanges.satisfiable(beyond)).isFalse();
    }

    @Test
    void downloadFileNamesAreSanitized() {
        assertThat(ContentResponses.safeFileName("report.pdf")).isEqualTo("report.pdf");
        assertThat(ContentResponses.safeFileName("a/b\\c\r\n.txt")).isEqualTo("a_b_c__.txt");
        assertThat(ContentResponses.safeFileName("\u0007")).isEqualTo("_");
        assertThat(ContentResponses.safeFileName(" ")).isEqualTo("download");
        assertThat(ContentResponses.safeFileName("x".repeat(300))).hasSize(255);
    }
}
