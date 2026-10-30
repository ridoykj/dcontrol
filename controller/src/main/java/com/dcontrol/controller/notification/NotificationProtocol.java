package com.dcontrol.controller.notification;

import com.dcontrol.controller.common.ws.RelayedCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Notification mirroring payloads (ws-v1.md, Phase 2). Notifications are relayed live and never stored. */
public final class NotificationProtocol {

    private NotificationProtocol() {
    }

    public enum Access { GRANTED, MISSING }

    public record Notification(
            @NotBlank @Size(max = 512) String key,
            @NotBlank @Size(max = 255) String packageName,
            @NotBlank @Size(max = 100) String appLabel,
            @Pattern(regexp = "^[0-9a-f]{64}$") String iconHash,
            @Size(max = 200) String title,
            @Size(max = 2000) String text,
            @Size(max = 200) String subText,
            @NotNull Instant postedAt,
            @NotNull Boolean ongoing,
            @NotNull Boolean clearable,
            @NotNull Boolean canReply) {
    }

    public record ListCommand(@NotNull UUID deviceId, @Min(0) int offset) implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return Map.of("offset", offset);
        }
    }

    public record ListResult(
            @NotNull Access access,
            @NotNull @Size(max = 200) List<@NotNull @Valid Notification> items,
            @PositiveOrZero Integer nextOffset) {
    }

    public record DismissCommand(@NotNull UUID deviceId, @NotBlank @Size(max = 512) String key)
            implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return Map.of("key", key);
        }
    }

    public record ReplyCommand(
            @NotNull UUID deviceId,
            @NotBlank @Size(max = 512) String key,
            @NotBlank @Size(max = 1000) String text) implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return Map.of("key", key, "text", text);
        }
    }

    public record Removed(@NotBlank @Size(max = 512) String key) {
    }

    public record PostedForWeb(UUID deviceId, Notification notification) {
    }

    public record RemovedForWeb(UUID deviceId, String key) {
    }
}
