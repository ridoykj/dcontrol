package com.dcontrol.controller.camera;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Mp4DurationTest {

    @TempDir
    Path dir;

    private OptionalLong millis(byte[] bytes) throws IOException {
        Path file = Files.write(dir.resolve("clip.mp4"), bytes);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return Mp4Duration.millis(channel);
        }
    }

    @Test
    void readsTheLengthFromBothMvhdVersions() throws IOException {
        assertThat(millis(Mp4Fixtures.mp4(1_500, 0))).hasValue(1_500);
        assertThat(millis(Mp4Fixtures.mp4(61_250, 1))).hasValue(61_250);
    }

    @Test
    void filesWithoutAReadableMvhdHaveNoLength() throws IOException {
        byte[] clip = Mp4Fixtures.mp4(1_000, 0);
        assertThat(millis(Arrays.copyOf(clip, 40))).isEmpty();
        assertThat(millis(Arrays.copyOf(clip, clip.length - 90))).isEmpty();
        assertThat(millis(Mp4Fixtures.JPEG)).isEmpty();

        byte[] oversizedBox = clip.clone();
        oversizedBox[16] = 0x7f;
        assertThat(millis(oversizedBox)).isEmpty();
    }
}
