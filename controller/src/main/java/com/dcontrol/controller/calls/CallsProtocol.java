package com.dcontrol.controller.calls;

import com.dcontrol.controller.common.validation.DialNumber;
import com.dcontrol.controller.common.validation.PageCursor;
import com.dcontrol.controller.common.validation.PhoneNumber;
import com.dcontrol.controller.common.ws.RelayedCommand;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code calls.*}, {@code call.place} and {@code telephony.sims} payloads (ws-v1.md, Phase 3). */
public final class CallsProtocol {

    private CallsProtocol() {
    }

    public enum CallType { INCOMING, OUTGOING, MISSED, VOICEMAIL, REJECTED, BLOCKED, ANSWERED_EXTERNALLY, OTHER }

    public enum Presentation { ALLOWED, RESTRICTED, UNKNOWN, PAYPHONE }

    public enum Access { GRANTED, MISSING }

    public record CallEntry(
            @NotNull @PositiveOrZero Long id,
            @PhoneNumber String number,
            @NotNull Presentation presentation,
            @Size(max = 200) String name,
            @NotNull CallType type,
            @NotNull Instant date,
            @NotNull @PositiveOrZero Long durationSec,
            @Size(max = 100) String simLabel,
            @NotNull Boolean isNew) {
    }

    public record ListCommand(@NotNull UUID deviceId, CallType type, @PageCursor String cursor)
            implements RelayedCommand.Targeted {

        @JsonIgnore
        @AssertTrue(message = "OTHER is not a valid filter")
        public boolean isFilterable() {
            return type != CallType.OTHER;
        }

        @Override
        public Object toDevice() {
            return new ListToDevice(type, cursor);
        }
    }

    public record ListToDevice(CallType type, String cursor) {
    }

    public record ListResult(
            @NotNull @Size(max = 500) List<@NotNull @Valid CallEntry> items, @PageCursor String nextCursor) {
    }

    public record DeleteCommand(@NotNull UUID deviceId, @NotNull @Size(min = 1, max = 100) List<@NotNull @PositiveOrZero Long> ids)
            implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return Map.of("ids", ids);
        }
    }

    public record DeleteResult(@NotNull @PositiveOrZero Integer deleted) {
    }

    public record PlaceCommand(@NotNull UUID deviceId, @DialNumber String number, Integer simId)
            implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return new PlaceToDevice(DialNumber.Validator.normalize(number), simId);
        }
    }

    public record PlaceToDevice(String number, Integer simId) {
    }

    public record SimsCommand(@NotNull UUID deviceId) implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return Map.of();
        }
    }

    public record Sim(
            @NotNull Integer id,
            @NotBlank @Size(max = 100) String label,
            @NotNull @Min(0) @Max(16) Integer slot,
            @Size(max = 100) String carrier) {
    }

    public record SimsResult(
            @NotNull Boolean hasTelephony,
            @NotNull Access access,
            Integer defaultSmsSimId,
            Integer defaultCallSimId,
            @NotNull @Size(max = 8) List<@NotNull @Valid Sim> sims) {
    }

    /** {@code calls.changed} for the browser. */
    public record ChangedForWeb(UUID deviceId) {
    }
}
