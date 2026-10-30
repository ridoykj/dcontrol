package com.dcontrol.controller.common.blob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dcontrol.controller.common.error.ApiException;
import com.dcontrol.controller.common.error.ErrorCode;
import com.dcontrol.controller.common.ws.RelayException;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class PhoneBlobRelayTest {

    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 16, 'J', 'F', 'I', 'F'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0};

    private final PhoneBlobRelay relay = new PhoneBlobRelay(64, Duration.ofSeconds(5));
    private final UUID device = UUID.randomUUID();

    private static ByteArrayInputStream body(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    @Test
    void aSlotIsFilledOnceByItsOwnDevice() {
        PhoneBlobRelay.Slot slot = relay.open(device);

        assertThatThrownBy(() -> relay.fill(UUID.randomUUID(), slot.uploadId(), body(JPEG)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(slot.blob()).isNotDone();

        relay.fill(device, slot.uploadId(), body(JPEG));
        PhoneBlobRelay.Blob blob = slot.blob().join();
        assertThat(blob.bytes()).isEqualTo(JPEG);
        assertThat(blob.type()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(relay.openSlots()).isZero();

        assertThatThrownBy(() -> relay.fill(device, slot.uploadId(), body(JPEG)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void theTypeComesFromTheMagicBytes() {
        PhoneBlobRelay.Slot slot = relay.open(device);
        relay.fill(device, slot.uploadId(), body(PNG));
        assertThat(slot.blob().join().type()).isEqualTo(MediaType.IMAGE_PNG);
    }

    @Test
    void rejectedUploadsUseUpTheSlotAndFailTheWaiter() {
        PhoneBlobRelay.Slot notImage = relay.open(device);
        assertThatThrownBy(() -> relay.fill(device, notImage.uploadId(), body("<svg/>".getBytes())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        assertThatThrownBy(() -> notImage.blob().join()).hasCauseInstanceOf(RelayException.class);

        PhoneBlobRelay.Slot tooLarge = relay.open(device);
        byte[] huge = new byte[65];
        System.arraycopy(JPEG, 0, huge, 0, JPEG.length);
        assertThatThrownBy(() -> relay.fill(device, tooLarge.uploadId(), body(huge)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
        assertThat(tooLarge.blob()).isCompletedExceptionally();
        assertThat(relay.openSlots()).isZero();
    }

    @Test
    void failedAndExpiredSlotsAreRemoved() {
        PhoneBlobRelay.Slot failed = relay.open(device);
        relay.fail(failed.uploadId(), new RelayException(ErrorCode.NOT_FOUND, "No photo"));
        assertThatThrownBy(() -> failed.blob().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RelayException.class);

        PhoneBlobRelay quick = new PhoneBlobRelay(64, Duration.ofMillis(50));
        PhoneBlobRelay.Slot expired = quick.open(device);
        assertThatThrownBy(() -> expired.blob().join()).hasCauseInstanceOf(TimeoutException.class);
        assertThat(quick.openSlots()).isZero();
        assertThatThrownBy(() -> quick.fill(device, expired.uploadId(), body(JPEG))).isInstanceOf(ApiException.class);
    }
}
