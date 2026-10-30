package com.dcontrol.controller.files;

import com.dcontrol.controller.common.validation.DevicePath;
import com.dcontrol.controller.common.validation.FileName;
import com.dcontrol.controller.common.ws.RelayedCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code files.*} payloads (ws-v1.md, Phase 2). Phone results carry the same rules as browser input. */
public final class FilesProtocol {

    private FilesProtocol() {
    }

    public record Volume(
            @NotBlank @Size(max = 64) String id,
            @NotBlank @Size(max = 100) String label,
            @DevicePath String path,
            @NotNull @PositiveOrZero Long totalBytes,
            @NotNull @PositiveOrZero Long freeBytes,
            @NotNull Boolean removable,
            @NotNull Boolean primary) {
    }

    public record FileEntry(
            @FileName String name,
            @DevicePath String path,
            @NotNull Boolean dir,
            @NotNull @PositiveOrZero Long size,
            @NotNull Instant modified,
            @Size(max = 127) String mime,
            @NotNull Boolean hidden) {
    }

    public record RootsCommand(@NotNull UUID deviceId) implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return Map.of();
        }
    }

    public record RootsResult(@NotNull @Size(max = 16) List<@NotNull @Valid Volume> volumes) {
    }

    public record ListCommand(@NotNull UUID deviceId, @DevicePath String path, @Min(0) int offset)
            implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return Map.of("path", path, "offset", offset);
        }
    }

    public record ListResult(
            @DevicePath String path,
            @NotNull @Size(max = 1000) List<@NotNull @Valid FileEntry> entries,
            @NotNull @PositiveOrZero Integer total,
            @PositiveOrZero Integer nextOffset) {
    }

    public record MkdirCommand(@NotNull UUID deviceId, @DevicePath String path, @FileName String name)
            implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return Map.of("path", path, "name", name);
        }
    }

    public record RenameCommand(@NotNull UUID deviceId, @DevicePath String path, @FileName String newName)
            implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return Map.of("path", path, "newName", newName);
        }
    }

    public record EntryResult(@NotNull @Valid FileEntry entry) {
    }

    public record DeleteCommand(@NotNull UUID deviceId, @NotNull @Size(min = 1, max = 100) List<@DevicePath String> paths)
            implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return Map.of("paths", paths);
        }
    }

    public record DeleteFailure(
            @DevicePath String path,
            @NotBlank @Size(max = 32) String code,
            @Size(max = 200) String message) {
    }

    public record DeleteResult(
            @NotNull @PositiveOrZero Integer deleted,
            @NotNull @Size(max = 100) List<@NotNull @Valid DeleteFailure> failures) {
    }
}
