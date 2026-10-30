package com.dcontrol.controller.transfer;

import com.dcontrol.controller.common.validation.FileName;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Transfer control messages on the device socket (ws-v1.md, Phase 2). Contents go over HTTP. */
public final class TransferProtocol {

    private TransferProtocol() {
    }

    public record SendCommand(UUID transferId, String path) {
    }

    public record SendResult(@FileName String fileName, @NotNull @PositiveOrZero Long size, @NotNull Instant modified) {
    }

    public record ReceiveCommand(UUID transferId, String targetDir, String fileName, long size, String sha256) {
    }

    public record Cancel(UUID transferId) {
    }

    public record Progress(@NotNull UUID transferId, @NotNull @PositiveOrZero Long bytes) {
    }

    public record Finished(
            @NotNull UUID transferId,
            @NotNull Boolean ok,
            @Size(max = 32) String errorCode,
            @Size(max = 200) String message) {
    }

    public record ProgressForWeb(UUID deviceId, UUID transferId, long bytes, long size) {
    }
}
