package com.dcontrol.controller.sms;

import com.dcontrol.controller.common.validation.DialNumber;
import com.dcontrol.controller.common.validation.PageCursor;
import com.dcontrol.controller.common.validation.PhoneNumber;
import com.dcontrol.controller.common.ws.RelayedCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code sms.*} payloads (ws-v1.md, Phase 3). Message text is never logged or stored. */
public final class SmsProtocol {

    public static final int MAX_SEND_TEXT = 1600;
    public static final int MAX_BODY = 2000;

    private SmsProtocol() {
    }

    public enum Kind { SMS, MMS }

    public enum Box { INBOX, SENT, OUTBOX, FAILED, QUEUED, DRAFT }

    public enum Status { NONE, PENDING, COMPLETE, FAILED }

    public enum SendStatus { SENT, DELIVERED, FAILED }

    public record Participant(@NotNull @PhoneNumber String address, @Size(max = 200) String name) {
    }

    public record SmsThread(
            @NotNull @PositiveOrZero Long threadId,
            @NotNull @Size(min = 1, max = 10) List<@NotNull @Valid Participant> participants,
            @Size(max = 200) String snippet,
            @NotNull Instant date,
            @NotNull @PositiveOrZero Integer unreadCount,
            @NotNull @PositiveOrZero Integer messageCount,
            @NotNull Boolean hasMms) {
    }

    public record Message(
            @NotNull @PositiveOrZero Long id,
            @NotNull Kind kind,
            @NotNull Box box,
            @PhoneNumber String address,
            @Size(max = MAX_BODY) String body,
            @NotNull Instant date,
            @NotNull Boolean read,
            @NotNull Status status,
            @Size(max = 100) String simLabel,
            @Size(max = 200) String mmsSummary) {
    }

    public record ThreadsCommand(@NotNull UUID deviceId, @PageCursor String cursor) implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return new ThreadsToDevice(cursor);
        }
    }

    public record ThreadsToDevice(String cursor) {
    }

    public record ThreadsResult(
            @NotNull @Size(max = 200) List<@NotNull @Valid SmsThread> items, @PageCursor String nextCursor) {
    }

    public record MessagesCommand(@NotNull UUID deviceId, @NotNull @PositiveOrZero Long threadId, @PageCursor String cursor)
            implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return new MessagesToDevice(threadId, cursor);
        }
    }

    public record MessagesToDevice(Long threadId, String cursor) {
    }

    public record MessagesResult(
            @NotNull @PositiveOrZero Long threadId,
            @NotNull @Size(max = 500) List<@NotNull @Valid Message> items,
            @PageCursor String nextCursor) {
    }

    public record SendCommand(
            @NotNull UUID deviceId,
            @DialNumber String to,
            @NotEmpty @Size(max = MAX_SEND_TEXT) String text,
            Integer simId,
            @NotNull UUID clientRef) implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return new SendToDevice(DialNumber.Validator.normalize(to), text, simId, clientRef);
        }
    }

    public record SendToDevice(String to, String text, Integer simId, UUID clientRef) {
    }

    public record SendResult(@NotNull @Min(1) @Max(20) Integer parts) {
    }

    public record Received(@NotNull @PositiveOrZero Long threadId, @NotNull @Valid Message message) {
    }

    public record ReceivedForWeb(UUID deviceId, Long threadId, Message message) {
    }

    public record StatusEvent(@NotNull UUID clientRef, @NotNull SendStatus status, @Size(max = 64) String errorCode) {
    }

    public record StatusForWeb(UUID deviceId, UUID clientRef, SendStatus status, String errorCode) {
    }

    public record ChangedForWeb(UUID deviceId) {
    }
}
