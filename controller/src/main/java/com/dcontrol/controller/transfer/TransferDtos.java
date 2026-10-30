package com.dcontrol.controller.transfer;

import com.dcontrol.controller.common.validation.DevicePath;
import com.dcontrol.controller.common.validation.FileName;
import com.dcontrol.controller.transfer.FileTransfer.Direction;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.UUID;

public final class TransferDtos {

    private TransferDtos() {
    }

    /**
     * {@code TO_DEVICE} needs {@code targetDir}, {@code fileName} and {@code size};
     * {@code FROM_DEVICE} needs {@code path}.
     */
    public record CreateRequest(
            @NotNull Direction direction,
            @Pattern(regexp = DevicePath.REGEX) String targetDir,
            @Pattern(regexp = FileName.REGEX) String fileName,
            @PositiveOrZero Long size,
            @Pattern(regexp = DevicePath.REGEX) String path) {

        @JsonIgnore
        @AssertTrue(message = "TO_DEVICE needs targetDir, fileName and size; FROM_DEVICE needs path")
        public boolean isComplete() {
            if (direction == null) {
                return true;
            }
            return direction == Direction.TO_DEVICE
                    ? targetDir != null && fileName != null && size != null
                    : path != null;
        }
    }

    public record TransferView(
            UUID id,
            UUID deviceId,
            Direction direction,
            TransferState state,
            String fileName,
            String devicePath,
            Long size,
            long receivedBytes,
            String sha256,
            String errorCode,
            Instant createdAt,
            Instant expiresAt,
            Instant finishedAt) {

        public static TransferView of(FileTransfer t) {
            return new TransferView(t.getId(), t.getDeviceKey(), t.getDirection(), t.getState(), t.getFileName(),
                    t.getDevicePath(), t.getSize(), t.getReceivedBytes(), t.getSha256(), t.getErrorCode(),
                    t.getCreatedAt(), t.getExpiresAt(), t.getFinishedAt());
        }
    }

    public record DownloadTicketResponse(String ticket, String url, Instant expiresAt) {
    }
}
